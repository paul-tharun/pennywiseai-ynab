package com.pennywiseai.ynab.capture

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.pennywiseai.ynab.capture.notify.Notifier
import com.pennywiseai.ynab.data.state.PostingStateStore
import com.pennywiseai.ynab.pipeline.PipelineResult
import com.pennywiseai.ynab.pipeline.TransactionPipeline
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Real-time post path (ADR-0003): parse->post ONE SMS off the main thread. First attempt
 * and every retry run the same code (idempotent via import_id). A retryable failure
 * (offline / 429 / 5xx) reschedules with backoff until MAX_ATTEMPTS (~24h ceiling), then
 * turns terminal. Terminal failures notify — but a paused pipeline notifies "paused",
 * not a per-message failure (design spec, Notifications).
 */
@HiltWorker
class SmsPostWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val pipeline: TransactionPipeline,
    private val postingState: PostingStateStore,
    private val notifier: Notifier,
) : CoroutineWorker(appContext, params) {

    // Only used when expedited work runs as a foreground service on Android <= 11.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifier.backfillForegroundInfo(done = 0, total = 0)

    override suspend fun doWork(): Result {
        val body = inputData.getString(KEY_BODY) ?: return Result.failure()
        val sender = inputData.getString(KEY_SENDER) ?: return Result.failure()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, 0L)

        return when (val result = pipeline.process(body, sender, timestamp)) {
            is PipelineResult.Failed -> {
                if (result.retryable && runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    // Terminal: distinguish a paused pipeline (surface pause) from a real failure.
                    if (postingState.isPaused()) notifier.notifyPaused() else notifier.notifyTerminalFailure(sender)
                    Result.failure()
                }
            }
            // Dropped / Skipped / Posted are all "handled" — nothing to reschedule.
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_BODY = "body"
        const val KEY_SENDER = "sender"
        const val KEY_TIMESTAMP = "timestamp"

        /** ~24h of exponential backoff from a 10s floor (design spec, Error handling). */
        const val MAX_ATTEMPTS = 8
    }
}
