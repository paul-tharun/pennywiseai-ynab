package com.pennywiseai.ynab.pipeline

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.core.TransactionMapper
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.data.state.FakePostingStateStore
import com.pennywiseai.ynab.data.token.FakeTokenStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId

class TransactionPipelineTest {

    private val mapper = TransactionMapper(ZoneId.of("UTC"))
    private val resolver = MappingResolver()
    private val ruleDao = FakeMappingRuleDao(
        mutableListOf(
            MappingRuleEntity(id = 1, bankName = "HDFC Bank", last4 = "1234", budgetId = "b1", accountId = "a1", currencyCode = "INR"),
        ),
    )
    private val logDao = FakeProcessedMessageDao()
    private val tokenStore = FakeTokenStore("valid-pat")
    private val postingState = FakePostingStateStore()
    private val poster = FakeTransactionPoster()

    private var nextParsed: ParsedTransaction? = null

    private fun pipeline() = TransactionPipeline(
        smsParser = SmsParser { _, _, _ -> nextParsed },
        mapper = mapper, resolver = resolver, poster = poster,
        mappingRuleDao = ruleDao, processedMessageDao = logDao,
        tokenStore = tokenStore, postingState = postingState,
    )

    private fun parsed(
        type: TransactionType = TransactionType.EXPENSE,
        bank: String = "HDFC Bank",
        last4: String? = "1234",
        currency: String = "INR",
    ) = ParsedTransaction(
        amount = BigDecimal("100.00"), type = type, merchant = "Coffee", reference = "ref1",
        accountLast4 = last4, balance = null, smsBody = "spent Rs 100 at Coffee ref1",
        sender = "VM-HDFCBK", timestamp = 1_753_000_000_000L, bankName = bank, currency = currency,
    )

    private suspend fun onlyRow(): ProcessedMessageEntity {
        val all = logDao.getAll()
        assertEquals(1, all.size)
        return all.single()
    }

    @Test
    fun `unparseable message is dropped and never logged`() = runTest {
        nextParsed = null
        assertEquals(PipelineResult.Dropped, pipeline().process("junk", "SENDER", 1L))
        assertEquals(0, logDao.getAll().size)
        assertEquals(0, poster.calls)
    }

    @Test
    fun `non-postable type is skipped as SKIPPED_NON_TRANSACTION`() = runTest {
        nextParsed = parsed(type = TransactionType.TRANSFER)
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Skipped(MessageStatus.SKIPPED_NON_TRANSACTION), result)
        assertEquals(MessageStatus.SKIPPED_NON_TRANSACTION, onlyRow().status)
        assertEquals(0, poster.calls)
    }

    @Test
    fun `no matching rule is skipped as SKIPPED_UNROUTED and records bank and last4`() = runTest {
        nextParsed = parsed(bank = "ICICI Bank", last4 = "9999")
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Skipped(MessageStatus.SKIPPED_UNROUTED), result)
        val row = onlyRow()
        assertEquals(MessageStatus.SKIPPED_UNROUTED, row.status)
        assertEquals("ICICI Bank", row.bankName)
        assertEquals("9999", row.last4)
        assertEquals(0, poster.calls)
    }

    @Test
    fun `a broken route fails fast as SKIPPED_UNROUTED without hitting the network`() = runTest {
        // The only matching rule is broken -> no network, logged SKIPPED_UNROUTED.
        val brokenDao = FakeMappingRuleDao(
            mutableListOf(
                MappingRuleEntity(id = 1, bankName = "HDFC Bank", last4 = "1234", budgetId = "b1", accountId = "a1", currencyCode = "INR", broken = true),
            ),
        )
        val pipeline = TransactionPipeline(
            smsParser = SmsParser { _, _, _ -> parsed() }, mapper = mapper, resolver = resolver, poster = poster,
            mappingRuleDao = brokenDao, processedMessageDao = logDao, tokenStore = tokenStore, postingState = postingState,
        )
        val result = pipeline.process("b", "s", 1L)
        assertEquals(PipelineResult.Skipped(MessageStatus.SKIPPED_UNROUTED), result)
        assertEquals(MessageStatus.SKIPPED_UNROUTED, onlyRow().status)
        assertEquals(0, poster.calls) // fail-fast: never posted
    }

    @Test
    fun `currency mismatch is skipped as SKIPPED_CURRENCY_MISMATCH`() = runTest {
        nextParsed = parsed(currency = "USD")
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Skipped(MessageStatus.SKIPPED_CURRENCY_MISMATCH), result)
        assertEquals(MessageStatus.SKIPPED_CURRENCY_MISMATCH, onlyRow().status)
        assertEquals(0, poster.calls)
    }

    @Test
    fun `happy path posts and records POSTED with the mapped budget`() = runTest {
        nextParsed = parsed()
        poster.outcome = PostOutcome.Posted
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Posted, result)
        assertEquals(MessageStatus.POSTED, onlyRow().status)
        assertEquals(1, poster.calls)
        assertEquals("b1", poster.lastBudgetId)
        assertEquals(1, poster.lastTransactions.size)
        assertEquals("a1", poster.lastTransactions.single().accountId)
        assertNull(onlyRow().error)
    }

    @Test
    fun `already-posted message is not re-posted`() = runTest {
        val p = parsed()
        nextParsed = p
        logDao.upsert(
            ProcessedMessageEntity(
                importId = mapper.importIdFor(p), sender = p.sender, bankName = p.bankName, last4 = p.accountLast4,
                amount = p.amount, currency = p.currency, status = MessageStatus.POSTED, timestamp = p.timestamp,
            ),
        )
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Posted, result)
        assertEquals(0, poster.calls)
    }

    @Test
    fun `a prior non-POSTED row does not block a retry`() = runTest {
        // Dedup only short-circuits on POSTED; a FAILED (or SKIPPED_*) row for this
        // importId must still be retried through to the poster.
        val p = parsed()
        nextParsed = p
        logDao.upsert(
            ProcessedMessageEntity(
                importId = mapper.importIdFor(p), sender = p.sender, bankName = p.bankName, last4 = p.accountLast4,
                amount = p.amount, currency = p.currency, status = MessageStatus.FAILED,
                error = "HTTP 429 - rate limited", timestamp = p.timestamp,
            ),
        )
        poster.outcome = PostOutcome.Posted
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Posted, result)
        assertEquals(1, poster.calls)
        assertEquals(MessageStatus.POSTED, onlyRow().status)
    }

    @Test
    fun `no token pauses posting and records FAILED without hitting the network`() = runTest {
        tokenStore.clear()
        nextParsed = parsed()
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Failed(retryable = false), result)
        assertEquals(MessageStatus.FAILED, onlyRow().status)
        assertEquals(TransactionPipeline.ERROR_NO_TOKEN, onlyRow().error)
        assertTrue(postingState.isPaused())
        assertEquals(0, poster.calls)
    }

    @Test
    fun `already paused short-circuits before the network`() = runTest {
        postingState.setPaused(true)
        nextParsed = parsed()
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Failed(retryable = false), result)
        assertEquals(TransactionPipeline.ERROR_TOKEN_INVALID, onlyRow().error)
        assertEquals(0, poster.calls)
    }

    @Test
    fun `401 from the poster pauses posting and records FAILED`() = runTest {
        nextParsed = parsed()
        poster.outcome = PostOutcome.Unauthorized
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Failed(retryable = false), result)
        assertEquals(TransactionPipeline.ERROR_TOKEN_INVALID, onlyRow().error)
        assertTrue(postingState.isPaused())
    }

    @Test
    fun `a 404 marks the route broken and records the row terminal`() = runTest {
        nextParsed = parsed() // HDFC / 1234 -> the seeded rule
        poster.outcome = PostOutcome.RouteBroken
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Failed(retryable = false), result)
        assertEquals(MessageStatus.FAILED, onlyRow().status)
        assertEquals(TransactionPipeline.ERROR_ROUTE_BROKEN, onlyRow().error)
        assertTrue(ruleDao.getAll().single().broken) // persisted -> next message fails fast
        assertFalse(postingState.isPaused()) // 404 does NOT pause posting
    }

    @Test
    fun `a 404 on a bank wildcard rule marks the wildcard route broken`() = runTest {
        // The only matching rule is the bank wildcard (domain last4 == null, stored
        // as "") -> setBroken must fall back to WILDCARD_LAST4, not the message's last4.
        val wildcardDao = FakeMappingRuleDao(
            mutableListOf(
                MappingRuleEntity(id = 1, bankName = "HDFC Bank", last4 = "", budgetId = "bWild", accountId = "aWild", currencyCode = "INR"),
            ),
        )
        val pipeline = TransactionPipeline(
            smsParser = SmsParser { _, _, _ -> parsed(last4 = "5555") }, mapper = mapper, resolver = resolver, poster = poster,
            mappingRuleDao = wildcardDao, processedMessageDao = logDao, tokenStore = tokenStore, postingState = postingState,
        )
        poster.outcome = PostOutcome.RouteBroken
        val result = pipeline.process("b", "s", 1L)
        assertEquals(PipelineResult.Failed(retryable = false), result)
        assertTrue(wildcardDao.getAll().single().broken)
    }

    @Test
    fun `retryable failure records FAILED and reports retryable`() = runTest {
        nextParsed = parsed()
        poster.outcome = PostOutcome.Failed(retryable = true, error = "HTTP 429 - rate limited")
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Failed(retryable = true), result)
        assertEquals("HTTP 429 - rate limited", onlyRow().error)
        assertFalse(postingState.isPaused())
    }

    @Test
    fun `terminal failure records FAILED and reports not-retryable`() = runTest {
        nextParsed = parsed()
        poster.outcome = PostOutcome.Failed(retryable = false, error = "HTTP 400 - malformed request")
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Failed(retryable = false), result)
        assertEquals("HTTP 400 - malformed request", onlyRow().error)
    }

    @Test
    fun `exact last4 rule is preferred over a bank wildcard`() = runTest {
        ruleDao.insert(MappingRuleEntity(bankName = "HDFC Bank", last4 = "", budgetId = "bWild", accountId = "aWild", currencyCode = "INR"))
        nextParsed = parsed(last4 = "1234")
        pipeline().process("b", "s", 1L)
        assertEquals("b1", poster.lastBudgetId)
    }

    @Test
    fun `bank wildcard routes a message whose last4 has no exact rule`() = runTest {
        ruleDao.insert(MappingRuleEntity(bankName = "HDFC Bank", last4 = "", budgetId = "bWild", accountId = "aWild", currencyCode = "INR"))
        nextParsed = parsed(last4 = "5555")
        val result = pipeline().process("b", "s", 1L)
        assertEquals(PipelineResult.Posted, result)
        assertEquals("bWild", poster.lastBudgetId)
    }

    @Test
    fun `the real-time path re-reads the rules table for every message`() = runTest {
        // Only backfill classifies against a snapshot; process() must see a rule added
        // between two messages straight away.
        nextParsed = parsed(bank = "ICICI Bank", last4 = "9999")
        val pipeline = pipeline()
        assertEquals(PipelineResult.Skipped(MessageStatus.SKIPPED_UNROUTED), pipeline.process("b", "s", 1L))
        ruleDao.insert(
            MappingRuleEntity(bankName = "ICICI Bank", last4 = "9999", budgetId = "b2", accountId = "a2", currencyCode = "INR"),
        )
        assertEquals(PipelineResult.Posted, pipeline.process("b", "s", 2L))
        assertEquals("b2", poster.lastBudgetId)
    }

    @Test
    fun `logged importId is the PW-prefixed content id`() = runTest {
        val p = parsed()
        nextParsed = p
        pipeline().process("b", "s", 1L)
        assertEquals(mapper.importIdFor(p), onlyRow().importId)
        assertTrue(onlyRow().importId.startsWith("PW:"))
    }
}
