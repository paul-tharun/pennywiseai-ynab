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

    /** True while the unique backfill work is enqueued or running (drives the Cancel button). */
    fun backfillStatus(): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(BackfillWorker.WORK_NAME)
            .map { infos -> infos.any { it.state != WorkInfo.State.SUCCEEDED &&
                it.state != WorkInfo.State.FAILED && it.state != WorkInfo.State.CANCELLED } }

    /**
     * Re-drive one FAILED message by re-reading its 1 ms inbox window and re-running the
     * pipeline. Idempotent via import_id (ADR-0005): if it already POSTED it dedup-skips;
     * if the cause was fixed (token/route) it now posts. Uses the shared backfill work
     * name, so a retry while a full backfill runs is KEEP-ignored — re-tap after it ends.
     */
    fun retryMessage(timestamp: Long) = enqueueBackfill(timestamp, timestamp + 1)
}
