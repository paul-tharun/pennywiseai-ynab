package com.pennywiseai.ynab.ui.rules

/**
 * The UI's seam onto the backfill scheduler: enqueue a re-drive over [fromMillis, toMillis).
 * Kept a `fun interface` so view models depend on this narrow abstraction (not the whole
 * CaptureScheduler) and tests can capture the requested window. Provided by [UiModule] from
 * CaptureScheduler::enqueueBackfill. Introduced here in Task 7; reused by RulesViewModel (Task 8).
 */
fun interface BackfillEnqueuer {
    fun enqueue(fromMillis: Long, toMillis: Long)
}
