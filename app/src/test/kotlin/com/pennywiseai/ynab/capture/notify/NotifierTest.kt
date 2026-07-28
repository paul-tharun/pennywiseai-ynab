package com.pennywiseai.ynab.capture.notify

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotifierTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val notifier = Notifier(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `both channels are created`() {
        val ids = manager.notificationChannels.map { it.id }.toSet()
        assertTrue(ids.contains(Notifier.CHANNEL_PROGRESS))
        assertTrue(ids.contains(Notifier.CHANNEL_ALERTS))
    }

    @Test
    fun `backfill summary posts a notification`() {
        notifier.notifyBackfillSummary(BackfillSummary(posted = 3, skipped = 1, failed = 0))
        assertNotNull(shadowOf(manager).getNotification(Notifier.SUMMARY_NOTIFICATION_ID))
    }

    @Test
    fun `terminal failure posts a notification`() {
        notifier.notifyTerminalFailure("VM-HDFCBK")
        assertNotNull(shadowOf(manager).getNotification(Notifier.FAILURE_NOTIFICATION_ID))
    }

    @Test
    fun `paused posts a notification`() {
        notifier.notifyPaused()
        assertNotNull(shadowOf(manager).getNotification(Notifier.PAUSED_NOTIFICATION_ID))
    }

    @Test
    fun `foreground info carries the backfill notification id`() {
        val info = notifier.backfillForegroundInfo(done = 2, total = 10)
        assertTrue(info.notificationId == Notifier.BACKFILL_NOTIFICATION_ID)
        assertNotNull(info.notification)
    }
}
