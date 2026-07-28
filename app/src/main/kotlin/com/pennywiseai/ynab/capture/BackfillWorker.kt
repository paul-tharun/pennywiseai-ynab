package com.pennywiseai.ynab.capture

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.pennywiseai.ynab.capture.notify.Notifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * On-demand backfill (design spec + ADR-0004): a foreground dataSync worker that reads the
 * SMS inbox for a date range, runs BackfillProcessor (bulk POST grouped by budget), shows a
 * determinate progress notification, supports cooperative cancellation (stop after the
 * in-flight chunk via isStopped), and ends with the exception-only summary notification.
 * Overlap with real-time is safe (import_id dedup); "resume" isn't a mode — re-running the
 * same range is cheap because dedup skips what's done.
 */
@HiltWorker
class BackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reader: SmsInboxReader,
    private val processor: BackfillProcessor,
    private val notifier: Notifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = notifier.backfillForegroundInfo(0, 0)

    override suspend fun doWork(): Result {
        val from = inputData.getLong(KEY_FROM, 0L)
        val to = inputData.getLong(KEY_TO, Long.MAX_VALUE)

        setForeground(notifier.backfillForegroundInfo(done = 0, total = 0))

        val messages = reader.read(from, to)
        val summary = processor.run(
            messages = messages,
            onProgress = { done, total -> setForeground(notifier.backfillForegroundInfo(done, total)) },
            isCancelled = { isStopped },
        )

        notifier.notifyBackfillSummary(summary)
        return Result.success()
    }

    companion object {
        const val KEY_FROM = "from_millis"
        const val KEY_TO = "to_millis"
        const val WORK_NAME = "sms-backfill"
    }
}
