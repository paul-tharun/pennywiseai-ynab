package com.pennywiseai.ynab.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.pennywiseai.ynab.capture.notify.Notifier
import com.pennywiseai.ynab.data.state.FakePostingStateStore
import com.pennywiseai.ynab.pipeline.PipelineResult
import com.pennywiseai.ynab.pipeline.TransactionPipeline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsPostWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifier = Notifier(context)
    private val postingState = FakePostingStateStore()

    /** Build an SmsPostWorker whose pipeline.process(...) returns a fixed PipelineResult. */
    private fun worker(result: PipelineResult, attempt: Int = 1): SmsPostWorker {
        val pipeline = object : TransactionPipeline(
            smsParser = { _, _, _ -> null }, // unused: process() is overridden below
            mapper = com.pennywiseai.ynab.core.TransactionMapper(java.time.ZoneId.of("UTC")),
            resolver = com.pennywiseai.ynab.core.MappingResolver(),
            poster = com.pennywiseai.ynab.pipeline.FakeTransactionPoster(),
            mappingRuleDao = com.pennywiseai.ynab.pipeline.FakeMappingRuleDao(),
            processedMessageDao = com.pennywiseai.ynab.pipeline.FakeProcessedMessageDao(),
            tokenStore = com.pennywiseai.ynab.data.token.FakeTokenStore("t"),
            postingState = postingState,
        ) {
            override suspend fun process(body: String, sender: String, timestamp: Long) = result
        }
        return TestListenableWorkerBuilder<SmsPostWorker>(context)
            .setInputData(workDataOf(SmsPostWorker.KEY_BODY to "b", SmsPostWorker.KEY_SENDER to "S", SmsPostWorker.KEY_TIMESTAMP to 1L))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : androidx.work.WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SmsPostWorker(appContext, workerParameters, pipeline, postingState, notifier)
            })
            .build()
    }

    @Test
    fun `posted is success`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker(PipelineResult.Posted).doWork())
    }

    @Test
    fun `skipped is success`() = runTest {
        assertEquals(
            ListenableWorker.Result.success(),
            worker(PipelineResult.Skipped(com.pennywiseai.ynab.data.local.MessageStatus.SKIPPED_UNROUTED)).doWork(),
        )
    }

    @Test
    fun `retryable failure under the ceiling retries`() = runTest {
        assertEquals(ListenableWorker.Result.retry(), worker(PipelineResult.Failed(retryable = true), attempt = 1).doWork())
    }

    @Test
    fun `retryable failure at the ceiling is terminal failure`() = runTest {
        assertEquals(
            ListenableWorker.Result.failure(),
            worker(PipelineResult.Failed(retryable = true), attempt = SmsPostWorker.MAX_ATTEMPTS).doWork(),
        )
    }

    @Test
    fun `non-retryable failure is terminal failure`() = runTest {
        assertEquals(ListenableWorker.Result.failure(), worker(PipelineResult.Failed(retryable = false)).doWork())
    }
}
