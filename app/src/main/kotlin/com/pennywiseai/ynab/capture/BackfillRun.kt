package com.pennywiseai.ynab.capture

/**
 * In-app view of the unique backfill work's lifecycle, mapped from WorkManager's WorkInfo by
 * [CaptureScheduler.backfillRun]. Drives the Import progress bar and the Home re-scan result.
 * [Done] carries the terminal tally from the worker's output data.
 */
sealed interface BackfillRun {
    data object Idle : BackfillRun
    data class Running(val done: Int, val total: Int) : BackfillRun
    data class Done(val posted: Int, val skipped: Int, val failed: Int) : BackfillRun
}
