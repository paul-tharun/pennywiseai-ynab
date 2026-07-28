package com.pennywiseai.ynab.capture

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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
}
