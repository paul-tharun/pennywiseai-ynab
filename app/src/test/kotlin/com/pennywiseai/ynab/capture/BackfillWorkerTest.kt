package com.pennywiseai.ynab.capture

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.pennywiseai.ynab.capture.notify.Notifier
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.core.TransactionMapper
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import com.pennywiseai.ynab.data.state.FakePostingStateStore
import com.pennywiseai.ynab.data.token.FakeTokenStore
import com.pennywiseai.ynab.pipeline.FakeMappingRuleDao
import com.pennywiseai.ynab.pipeline.FakeProcessedMessageDao
import com.pennywiseai.ynab.pipeline.FakeTransactionPoster
import com.pennywiseai.ynab.pipeline.SmsParser
import com.pennywiseai.ynab.pipeline.TransactionPipeline
import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackfillWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifier = Notifier(context)
    private val logDao = FakeProcessedMessageDao()
    private val ruleDao = FakeMappingRuleDao(
        mutableListOf(
            MappingRuleEntity(id = 1, bankName = "HDFC Bank", last4 = "1234", budgetId = "b1", accountId = "a1", currencyCode = "INR"),
        ),
    )
    private val poster = FakeTransactionPoster()

    private val parser = SmsParser { body, sender, ts ->
        if (body.startsWith("HDFC")) ParsedTransaction(
            amount = BigDecimal("100.00"), type = TransactionType.EXPENSE, merchant = "M", reference = body,
            accountLast4 = "1234", balance = null, smsBody = body, sender = sender, timestamp = ts,
            bankName = "HDFC Bank", currency = "INR",
        ) else null
    }

    private fun pipeline() = TransactionPipeline(
        smsParser = parser, mapper = TransactionMapper(ZoneId.of("UTC")), resolver = MappingResolver(),
        poster = poster, mappingRuleDao = ruleDao, processedMessageDao = logDao,
        tokenStore = FakeTokenStore("t"), postingState = FakePostingStateStore(),
    )

    private val foreground = RecordingForegroundUpdater()

    private fun worker(reader: SmsInboxReader): BackfillWorker {
        val processor = BackfillProcessor(pipeline(), poster, logDao, ruleDao, FakePostingStateStore())
        return TestListenableWorkerBuilder<BackfillWorker>(context)
            .setInputData(workDataOf(BackfillWorker.KEY_FROM to 0L, BackfillWorker.KEY_TO to 1000L))
            .setForegroundUpdater(foreground)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, name: String, params: WorkerParameters) =
                    BackfillWorker(appContext, params, reader, processor, notifier)
            })
            .build()
    }

    private fun inbox(count: Int) =
        FakeSmsInboxReader((0 until count).map { RawSms("VM-HDFCBK", "HDFC $it", it.toLong()) })

    @Test
    fun `reads the inbox, posts, and fires the summary`() = runTest {
        val reader = FakeSmsInboxReader(listOf(RawSms("VM-HDFCBK", "HDFC a", 10L), RawSms("VM-HDFCBK", "HDFC b", 20L)))
        val result = worker(reader).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, poster.calls) // one bulk POST for budget b1
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(shadowOf(manager).getNotification(Notifier.SUMMARY_NOTIFICATION_ID))
    }

    @Test
    fun `empty range still succeeds and summarizes zero`() = runTest {
        val result = worker(FakeSmsInboxReader(emptyList())).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, poster.calls)
    }

    @Test
    fun `progress notifications are throttled to roughly one percent of the batch`() = runTest {
        val result = worker(inbox(250)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(3, poster.calls) // 250 messages really were processed (chunks of 100)
        // Un-throttled this would be 1 + 250 setForeground IPCs; the step is max(1, 250/100) = 2,
        // so the batch redraws at done = 2, 4, ... 250 -> 1 initial + 125 updates.
        assertTrue("expected far fewer updates than messages", foreground.updates.size < 250)
        assertEquals(126, foreground.updates.size)
        assertEquals("Scanning…", foreground.updates.first().text())
        assertEquals("250 / 250", foreground.updates.last().text()) // the final state always lands
    }

    @Test
    fun `a batch smaller than the step floor still updates per message`() = runTest {
        worker(inbox(5)).doWork()

        // max(1, 5/100) floors the step at 1 -> no progress is swallowed on small batches.
        assertEquals(6, foreground.updates.size) // 1 initial + 5
        assertEquals("5 / 5", foreground.updates.last().text())
    }

    /** Counts the real setForeground() IPCs the worker issues — what the throttle exists to cut. */
    private class RecordingForegroundUpdater : ForegroundUpdater {
        val updates = mutableListOf<ForegroundInfo>()

        override fun setForegroundAsync(
            context: Context,
            id: UUID,
            foregroundInfo: ForegroundInfo,
        ): ListenableFuture<Void> {
            updates += foregroundInfo
            return DoneFuture
        }
    }

    /** An already-completed Void future — the worker only awaits setForegroundAsync's result. */
    private object DoneFuture : ListenableFuture<Void> {
        override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
        override fun isDone() = true
        override fun get(): Void? = null
        override fun get(timeout: Long, unit: TimeUnit): Void? = null
    }
}

private fun ForegroundInfo.text(): String? =
    notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
