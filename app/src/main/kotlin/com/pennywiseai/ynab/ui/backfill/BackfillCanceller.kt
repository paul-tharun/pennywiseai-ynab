package com.pennywiseai.ynab.ui.backfill

/** Seam onto CaptureScheduler::cancelBackfill so BackfillViewModel stays unit-testable. */
fun interface BackfillCanceller {
    fun cancel()
}
