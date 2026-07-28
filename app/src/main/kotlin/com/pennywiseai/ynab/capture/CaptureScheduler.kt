package com.pennywiseai.ynab.capture

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Enqueues the capture WorkManager jobs (the API Plan 6's UI and Task 5's receiver call).
 * Real-time is expedited + single-message; backfill (Task 8) is a foreground worker.
 */
@Singleton
class CaptureScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Enqueue an expedited post for one received SMS. Unique-per-message so a duplicated
     *  broadcast doesn't double-process (post is idempotent regardless via import_id). */
    fun enqueueRealtime(body: String, sender: String, timestamp: Long) {
        val request = OneTimeWorkRequestBuilder<SmsPostWorker>()
            .setInputData(
                workDataOf(
                    SmsPostWorker.KEY_BODY to body,
                    SmsPostWorker.KEY_SENDER to sender,
                    SmsPostWorker.KEY_TIMESTAMP to timestamp,
                ),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        val uniqueName = "realtime-${sender}-${timestamp}-${body.hashCode()}"
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }

    /** Enqueue a foreground backfill over [fromMillis, toMillis). Unique KEEP so a second
     *  request while one runs is ignored (re-running a range is safe but pointless). */
    fun enqueueBackfill(fromMillis: Long, toMillis: Long) {
        val request = OneTimeWorkRequestBuilder<BackfillWorker>()
            .setInputData(
                workDataOf(
                    BackfillWorker.KEY_FROM to fromMillis,
                    BackfillWorker.KEY_TO to toMillis,
                ),
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniqueWork(BackfillWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Cancel an in-flight backfill; the worker stops after the current chunk (isStopped). */
    fun cancelBackfill() {
        workManager.cancelUniqueWork(BackfillWorker.WORK_NAME)
    }

    /**
     * Maps the unique backfill work to a [BackfillRun] for in-app UI. ENQUEUED/RUNNING/BLOCKED
     * -> Running(done,total) read from live progress data (0/0 until the worker reports).
     * SUCCEEDED -> Done(tally) from the worker's output data. FAILED/CANCELLED and "no work"
     * -> Idle. First (most recent) info wins; unique work usually has exactly one.
     */
    fun backfillRun(): Flow<BackfillRun> =
        workManager.getWorkInfosForUniqueWorkFlow(BackfillWorker.WORK_NAME).map { infos ->
            val info = infos.firstOrNull() ?: return@map BackfillRun.Idle
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
                    BackfillRun.Running(
                        done = info.progress.getInt(BackfillWorker.KEY_PROGRESS_DONE, 0),
                        total = info.progress.getInt(BackfillWorker.KEY_PROGRESS_TOTAL, 0),
                    )
                WorkInfo.State.SUCCEEDED -> BackfillRun.Done(
                    posted = info.outputData.getInt(BackfillWorker.KEY_RESULT_POSTED, 0),
                    skipped = info.outputData.getInt(BackfillWorker.KEY_RESULT_SKIPPED, 0),
                    failed = info.outputData.getInt(BackfillWorker.KEY_RESULT_FAILED, 0),
                )
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> BackfillRun.Idle
            }
        }

    /**
     * Re-drive one FAILED message by re-reading its 1 ms inbox window and re-running the
     * pipeline. Idempotent via import_id (ADR-0005): if it already POSTED it dedup-skips;
     * if the cause was fixed (token/route) it now posts. Uses the shared backfill work
     * name, so a retry while a full backfill runs is KEEP-ignored — re-tap after it ends.
     */
    fun retryMessage(timestamp: Long) = enqueueBackfill(timestamp, timestamp + 1)
}
