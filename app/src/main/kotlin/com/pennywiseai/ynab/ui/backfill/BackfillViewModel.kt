package com.pennywiseai.ynab.ui.backfill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.ynab.capture.BackfillRun
import com.pennywiseai.ynab.ui.rules.BackfillEnqueuer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The DateRangePicker returns the end date at midnight; the inbox read is DATE < to, so add
 *  one day to include the whole selected end day. */
fun inclusiveEndMillis(endDateMillis: Long): Long = endDateMillis + 24L * 60 * 60 * 1000

/** The [now - days, now] window a quick-range chip imports. Pure — unit-tested without a clock. */
fun quickRangeMillis(nowMillis: Long, days: Int): Pair<Long, Long> =
    (nowMillis - days.toLong() * 24 * 60 * 60 * 1000) to nowMillis

@HiltViewModel
class BackfillViewModel @Inject constructor(
    private val enqueuer: BackfillEnqueuer,
    private val canceller: BackfillCanceller,
    private val observer: BackfillObserver,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val run: StateFlow<BackfillRun> =
        observer.status().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackfillRun.Idle)

    fun startQuickRange(days: Int) {
        val (from, to) = quickRangeMillis(now(), days)
        enqueuer.enqueue(from, to)
    }

    fun startCustom(fromMillis: Long, toDateMillis: Long) =
        enqueuer.enqueue(fromMillis, inclusiveEndMillis(toDateMillis))

    fun cancel() = canceller.cancel()
}
