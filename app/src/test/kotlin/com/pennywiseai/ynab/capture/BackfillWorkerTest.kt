package com.pennywiseai.ynab.capture

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.ZoneId

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

    private fun worker(reader: SmsInboxReader): BackfillWorker {
        val processor = BackfillProcessor(pipeline(), poster, logDao, ruleDao, FakePostingStateStore())
        return TestListenableWorkerBuilder<BackfillWorker>(context)
            .setInputData(workDataOf(BackfillWorker.KEY_FROM to 0L, BackfillWorker.KEY_TO to 1000L))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, name: String, params: WorkerParameters) =
                    BackfillWorker(appContext, params, reader, processor, notifier)
            })
            .build()
    }

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
}
