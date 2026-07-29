package com.pennywiseai.ynab.ui.backfill

import com.pennywiseai.ynab.capture.BackfillRun
import kotlinx.coroutines.flow.Flow

/**
 * Narrow seam onto the backfill lifecycle so view models depend on this, not the whole
 * CaptureScheduler, and tests can drive it with a fake Flow. Provided by UiModule from
 * CaptureScheduler::backfillRun. Consumed by HomeViewModel (re-scan result) and BackfillViewModel.
 */
fun interface BackfillObserver {
    fun status(): Flow<BackfillRun>
}
