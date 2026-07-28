package com.pennywiseai.ynab.capture

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.core.TransactionMapper
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import com.pennywiseai.ynab.data.state.FakePostingStateStore
import com.pennywiseai.ynab.data.token.FakeTokenStore
import com.pennywiseai.ynab.pipeline.FakeMappingRuleDao
import com.pennywiseai.ynab.pipeline.FakeProcessedMessageDao
import com.pennywiseai.ynab.pipeline.FakeTransactionPoster
import com.pennywiseai.ynab.pipeline.PostOutcome
import com.pennywiseai.ynab.pipeline.SmsParser
import com.pennywiseai.ynab.pipeline.TransactionPipeline
import com.pennywiseai.ynab.capture.notify.BackfillSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId

class BackfillProcessorTest {

    private val logDao = FakeProcessedMessageDao()
    private val ruleDao = FakeMappingRuleDao(
        mutableListOf(
            MappingRuleEntity(id = 1, bankName = "HDFC Bank", last4 = "1234", budgetId = "b1", accountId = "a1", currencyCode = "INR"),
            MappingRuleEntity(id = 2, bankName = "ICICI Bank", last4 = "5678", budgetId = "b2", accountId = "a2", currencyCode = "INR"),
        ),
    )
    private val postingState = FakePostingStateStore()
    private val poster = FakeTransactionPoster()

    // Parser keyed off the SMS body so each message maps to a distinct bank/reference.
    private val parser = SmsParser { body, sender, timestamp ->
        when {
            body.startsWith("HDFC") -> parsed("HDFC Bank", "1234", body, sender, timestamp)
            body.startsWith("ICICI") -> parsed("ICICI Bank", "5678", body, sender, timestamp)
            else -> null
        }
    }

    private fun parsed(bank: String, last4: String, body: String, sender: String, ts: Long) = ParsedTransaction(
        amount = BigDecimal("100.00"), type = TransactionType.EXPENSE, merchant = "M", reference = body,
        accountLast4 = last4, balance = null, smsBody = body, sender = sender, timestamp = ts,
        bankName = bank, currency = "INR",
    )

    private fun pipeline() = TransactionPipeline(
        smsParser = parser,
        mapper = TransactionMapper(ZoneId.of("UTC")),
        resolver = MappingResolver(),
        poster = poster, // classify() never posts; process() would, but backfill calls classify only
        mappingRuleDao = ruleDao, processedMessageDao = logDao,
        tokenStore = FakeTokenStore("valid-pat"), postingState = postingState,
    )

    private fun processor() = BackfillProcessor(pipeline(), poster, logDao, ruleDao, postingState)

    private fun sms(body: String, ts: Long) = RawSms("VM-BANK", body, ts)

    @Test
    fun `postables are grouped into one bulk POST per budget`() = runTest {
        val summary = processor().run(
            listOf(sms("HDFC a", 1), sms("HDFC b", 2), sms("ICICI c", 3)),
        )
        assertEquals(2, poster.calls) // one per budget, not per message
        assertEquals(3, summary.posted)
        assertEquals(setOf("b1", "b2"), poster.allCalls.map { it.first }.toSet())
    }

    @Test
    fun `unrouted and non-transaction are skipped and not posted`() = runTest {
        val summary = processor().run(listOf(sms("UNKNOWN x", 1), sms("HDFC ok", 2)))
        assertEquals(1, summary.posted)
        // "UNKNOWN" parses to null -> Dropped (not logged), so skipped stays 0 here.
        assertEquals(0, summary.skipped)
        assertEquals(MessageStatus.POSTED, logDao.getAll().single().status)
    }

    @Test
    fun `a duplicate import id in a 2xx chunk is POSTED`() = runTest {
        poster.outcome = PostOutcome.Posted // YNAB reports the dup inside a 2xx -> Posted
        val summary = processor().run(listOf(sms("HDFC dup", 1)))
        assertEquals(1, summary.posted)
    }

    @Test
    fun `chunk 400 falls back to individual posts - good rows POSTED, bad row FAILED`() = runTest {
        // First (bulk) call 400s; then per-element: the element whose ref contains BAD 400s, others 2xx.
        poster.responder = { _, txns ->
            when {
                txns.size > 1 -> PostOutcome.Failed(retryable = false, error = "HTTP 400")
                txns.single().memo?.contains("BAD") == true -> PostOutcome.Failed(retryable = false, error = "HTTP 400")
                else -> PostOutcome.Posted
            }
        }
        val summary = processor().run(
            listOf(sms("HDFC good1", 1), sms("HDFC BAD", 2), sms("HDFC good2", 3)),
        )
        assertEquals(2, summary.posted)
        assertEquals(1, summary.failed)
        // 1 bulk call + 3 individual retries = 4 poster calls.
        assertEquals(4, poster.calls)
    }

    @Test
    fun `retryable chunk failure marks the whole chunk FAILED`() = runTest {
        poster.outcome = PostOutcome.Failed(retryable = true, error = "HTTP 429")
        val summary = processor().run(listOf(sms("HDFC a", 1), sms("HDFC b", 2)))
        assertEquals(0, summary.posted)
        assertEquals(2, summary.failed)
        assertEquals(1, poster.calls) // no per-element fallback for a retryable failure
    }

    @Test
    fun `401 pauses posting and fails the chunk`() = runTest {
        poster.outcome = PostOutcome.Unauthorized
        val summary = processor().run(listOf(sms("HDFC a", 1)))
        assertEquals(1, summary.failed)
        assertEquals(true, postingState.isPaused())
    }

    @Test
    fun `404 marks the route broken and fails the chunk`() = runTest {
        poster.outcome = PostOutcome.RouteBroken
        processor().run(listOf(sms("HDFC a", 1)))
        assertEquals(true, ruleDao.getAll().single { it.bankName == "HDFC Bank" }.broken)
    }

    @Test
    fun `the rules table is read once per run, not once per message`() = runTest {
        val messages = (1..25).map { sms("HDFC $it", it.toLong()) }
        val summary = processor().run(messages)
        assertEquals(25, summary.posted) // all 25 really were classified and posted
        assertEquals(1, ruleDao.getAllCalls) // one snapshot, not 25 full-table reads
    }

    @Test
    fun `cancellation before posting stops the run`() = runTest {
        val summary = processor().run(
            messages = listOf(sms("HDFC a", 1), sms("HDFC b", 2)),
            isCancelled = { true }, // cancelled from the first check
        )
        assertEquals(0, poster.calls)
        assertEquals(BackfillSummary(0, 0, 0), summary)
    }
}
