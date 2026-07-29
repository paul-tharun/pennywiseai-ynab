package com.pennywiseai.ynab.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.ynab.capture.BackfillRun
import com.pennywiseai.ynab.capture.SmsInboxReader
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.ui.backfill.BackfillObserver
import com.pennywiseai.ynab.ui.rules.BackfillEnqueuer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Narrow seam over CaptureScheduler.retryMessage so the VM stays unit-testable. */
fun interface MessageRetrier {
    fun retry(timestamp: Long)
}

/** Home's top-of-screen health: per-status counts + the newest processed timestamp. */
data class HomeStats(
    val posted: Int,
    val failed: Int,
    val unrouted: Int,
    val lastActivityMillis: Long?,
)

/** Transient state of the Home ⟳ re-scan action. [Result] holds how many messages posted. */
sealed interface RescanState {
    data object Idle : RescanState
    data object Running : RescanState
    data class Result(val imported: Int) : RescanState
}

/**
 * State of an on-demand SMS-body preview for one row (keyed by importId). The body is
 * never persisted (ProcessedMessageEntity stores only routing fields); it is re-read from
 * the inbox when the user expands a row, and [Unavailable] when that SMS is gone / unreadable.
 */
sealed interface MessageBodyState {
    data object Loading : MessageBodyState
    data class Loaded(val body: String) : MessageBodyState
    data object Unavailable : MessageBodyState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dao: ProcessedMessageDao,
    private val retrier: MessageRetrier,
    private val enqueuer: BackfillEnqueuer,
    private val observer: BackfillObserver,
    private val inboxReader: SmsInboxReader,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _filter = MutableStateFlow<MessageStatus?>(null)
    val filter: StateFlow<MessageStatus?> = _filter

    /** Expanded body previews, keyed by importId. Absent = collapsed. */
    private val _bodies = MutableStateFlow<Map<String, MessageBodyState>>(emptyMap())
    val bodies: StateFlow<Map<String, MessageBodyState>> = _bodies

    val items: StateFlow<List<ProcessedMessageEntity>> =
        _filter.flatMapLatest { status ->
            if (status == null) dao.observeAll() else dao.observeByStatus(status)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Counts are derived from the full (unfiltered) stream so the tiles are stable while filtering. */
    val stats: StateFlow<HomeStats> =
        dao.observeAll().map { all ->
            HomeStats(
                posted = all.count { it.status == MessageStatus.POSTED },
                failed = all.count { it.status == MessageStatus.FAILED },
                unrouted = all.count { it.status == MessageStatus.SKIPPED_UNROUTED },
                lastActivityMillis = all.maxOfOrNull { it.timestamp },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeStats(0, 0, 0, null))

    private val _rescanState = MutableStateFlow<RescanState>(RescanState.Idle)
    val rescanState: StateFlow<RescanState> = _rescanState

    fun setFilter(status: MessageStatus?) { _filter.value = status }

    /**
     * Toggle the SMS-body preview for [item]. Collapsing removes it; expanding shows
     * [MessageBodyState.Loading] then re-reads the exact inbox row (matched by sender at
     * this message's millisecond) — [MessageBodyState.Loaded] on hit, else Unavailable.
     */
    fun toggleBody(item: ProcessedMessageEntity) {
        if (item.importId in _bodies.value) {
            _bodies.value = _bodies.value - item.importId // collapse
            return
        }
        _bodies.value = _bodies.value + (item.importId to MessageBodyState.Loading)
        viewModelScope.launch {
            // Content-hash import ids drop the timestamp, so re-locate the exact SMS by its
            // millisecond + sender: read the [ts, ts+1) inbox slice and match the sender.
            val body = inboxReader.read(item.timestamp, item.timestamp + 1)
                .firstOrNull { it.sender == item.sender }
                ?.body
            // Skip if the user collapsed the row while the read was in flight.
            if (item.importId !in _bodies.value) return@launch
            _bodies.value = _bodies.value + (item.importId to
                (body?.let { MessageBodyState.Loaded(it) } ?: MessageBodyState.Unavailable))
        }
    }

    /** Manual retry, available on any FAILED row: re-drive its inbox window (idempotent). */
    fun retry(item: ProcessedMessageEntity) = retrier.retry(item.timestamp)

    /**
     * Re-scan the last 24 hours to catch anything the real-time receiver missed. Idempotent
     * (import_id dedup), so it is safe to tap repeatedly. Waits for THIS run to actually go
     * Running before accepting a terminal state, so a stale terminal WorkInfo replay can't
     * short-circuit the result to a previous import's tally. Terminates on the first settled
     * state after that: Done -> the imported tally, Idle (CaptureScheduler's mapping of a
     * CANCELLED or FAILED run) -> back to Idle rather than hanging in Running forever.
     */
    fun rescan() {
        if (_rescanState.value == RescanState.Running) return
        _rescanState.value = RescanState.Running
        enqueuer.enqueue(now() - DAY_MILLIS, now())
        viewModelScope.launch {
            val settled = observer.status()
                .dropWhile { it !is BackfillRun.Running }
                .first { it !is BackfillRun.Running }
            _rescanState.value = when (settled) {
                is BackfillRun.Done -> RescanState.Result(imported = settled.posted)
                BackfillRun.Idle -> RescanState.Idle
                is BackfillRun.Running -> RescanState.Idle // unreachable: excluded by the predicate above
            }
        }
    }

    /** Dismiss the transient re-scan result (tapping ⟳ again also resets it). */
    fun clearRescanResult() { _rescanState.value = RescanState.Idle }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
