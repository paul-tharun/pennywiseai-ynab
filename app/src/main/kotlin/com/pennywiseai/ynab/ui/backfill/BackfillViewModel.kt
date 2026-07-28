package com.pennywiseai.ynab.ui.backfill

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.ynab.capture.BackfillRun
import com.pennywiseai.ynab.capture.CaptureScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The DateRangePicker returns the end date at midnight; the inbox read is DATE < to, so add
 *  one day to include the whole selected end day. */
fun inclusiveEndMillis(endDateMillis: Long): Long = endDateMillis + 24L * 60 * 60 * 1000

@HiltViewModel
class BackfillViewModel @Inject constructor(
    private val scheduler: CaptureScheduler,
) : ViewModel() {

    val running: StateFlow<Boolean> =
        scheduler.backfillRun().map { it is BackfillRun.Running }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun start(fromMillis: Long, toDateMillis: Long) =
        scheduler.enqueueBackfill(fromMillis, inclusiveEndMillis(toDateMillis))

    fun cancel() = scheduler.cancelBackfill()
}
