package com.pennywiseai.ynab.capture.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** posted / skipped / failed tallies from one backfill run (design spec, Notifications). */
data class BackfillSummary(val posted: Int, val skipped: Int, val failed: Int)

/**
 * Owns notification channels + the three exception-only notifications (design spec):
 * a determinate progress notification for the backfill foreground service, a one-shot
 * backfill summary, a terminal-failure alert, and a posting-paused alert. POSTED never
 * notifies. Uses framework drawables so no res asset is added. Posting no-ops without
 * POST_NOTIFICATIONS (API 33+) — the app still works via the in-app banner/history.
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    init {
        createChannels()
    }

    private fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // minSdk 26, so NotificationChannel is always available (no version guard).
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Import progress", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** The ongoing determinate notification WorkManager shows while the backfill runs. */
    fun backfillForegroundInfo(done: Int, total: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle("Importing transactions")
            .setContentText(if (total > 0) "$done / $total" else "Scanning…")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0) // indeterminate until total is known
            .build()
        return ForegroundInfo(
            BACKFILL_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /** One-shot end-of-backfill summary (design spec: "posted N · skipped M · failed K"). */
    fun notifyBackfillSummary(summary: BackfillSummary) {
        val text = "posted ${summary.posted} · skipped ${summary.skipped} · failed ${summary.failed}"
        post(
            SUMMARY_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setContentTitle("Backfill complete")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Terminal FAILED alert — only fired once a failure is out of its auto-retry window. */
    fun notifyTerminalFailure(sender: String) {
        post(
            FAILURE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setContentTitle("A transaction couldn't be posted")
                .setContentText("From $sender — open the app to retry.")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Posting-paused alert (401 / no token) — surfaced prominently (design spec). */
    fun notifyPaused() {
        post(
            PAUSED_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setContentTitle("Posting paused")
                .setContentText("Your YNAB token is missing or invalid. Update it to resume.")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun post(id: Int, notification: android.app.Notification) {
        // No-ops silently if POST_NOTIFICATIONS is not granted (API 33+).
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        const val CHANNEL_PROGRESS = "import_progress"
        const val CHANNEL_ALERTS = "alerts"
        const val BACKFILL_NOTIFICATION_ID = 1001
        const val SUMMARY_NOTIFICATION_ID = 1002
        const val FAILURE_NOTIFICATION_ID = 1003
        const val PAUSED_NOTIFICATION_ID = 1004
    }
}
