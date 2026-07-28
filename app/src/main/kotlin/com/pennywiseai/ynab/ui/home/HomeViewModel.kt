package com.pennywiseai.ynab.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.ynab.capture.BackfillRun
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dao: ProcessedMessageDao,
    private val retrier: MessageRetrier,
    private val enqueuer: BackfillEnqueuer,
    private val observer: BackfillObserver,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _filter = MutableStateFlow<MessageStatus?>(null)
    val filter: StateFlow<MessageStatus?> = _filter

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

    /** Manual retry, available on any FAILED row: re-drive its inbox window (idempotent). */
    fun retry(item: ProcessedMessageEntity) = retrier.retry(item.timestamp)

    /**
     * Re-scan the last 24 hours to catch anything the real-time receiver missed. Idempotent
     * (import_id dedup), so it is safe to tap repeatedly. Waits for THIS run to actually go
     * Running before accepting a Done, so a stale terminal WorkInfo replay can't short-circuit
     * the result to a previous import's tally.
     */
    fun rescan() {
        if (_rescanState.value == RescanState.Running) return
        _rescanState.value = RescanState.Running
        enqueuer.enqueue(now() - DAY_MILLIS, now())
        viewModelScope.launch {
            val done = observer.status()
                .dropWhile { it !is BackfillRun.Running }
                .first { it is BackfillRun.Done } as BackfillRun.Done
            _rescanState.value = RescanState.Result(imported = done.posted)
        }
    }

    /** Dismiss the transient re-scan result (tapping ⟳ again also resets it). */
    fun clearRescanResult() { _rescanState.value = RescanState.Idle }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
