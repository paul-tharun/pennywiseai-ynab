package com.pennywiseai.ynab.pipeline

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.ynab.core.MappingResolver
import com.pennywiseai.ynab.core.TransactionMapper
import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.data.state.FakePostingStateStore
import com.pennywiseai.ynab.data.token.FakeTokenStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId

class ClassificationSeamTest {

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

    private var nextParsed: ParsedTransaction? = null

    private fun pipeline() = TransactionPipeline(
        smsParser = SmsParser { _, _, _ -> nextParsed },
        mapper = mapper, resolver = resolver, poster = FakeTransactionPoster(),
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

    @Test
    fun `null parse is Dropped`() = runTest {
        nextParsed = null
        assertEquals(Classification.Dropped, pipeline().classify("junk", "S", 1L))
    }

    @Test
    fun `non-postable type classifies Skipped SKIPPED_NON_TRANSACTION`() = runTest {
        nextParsed = parsed(type = TransactionType.TRANSFER)
        val c = pipeline().classify("b", "s", 1L) as Classification.Skipped
        assertEquals(MessageStatus.SKIPPED_NON_TRANSACTION, c.status)
    }

    @Test
    fun `unrouted classifies Skipped SKIPPED_UNROUTED`() = runTest {
        nextParsed = parsed(bank = "ICICI Bank", last4 = "9999")
        val c = pipeline().classify("b", "s", 1L) as Classification.Skipped
        assertEquals(MessageStatus.SKIPPED_UNROUTED, c.status)
    }

    @Test
    fun `broken route classifies Skipped SKIPPED_UNROUTED without network`() = runTest {
        ruleDao.setBroken("HDFC Bank", "1234", true)
        nextParsed = parsed()
        val c = pipeline().classify("b", "s", 1L) as Classification.Skipped
        assertEquals(MessageStatus.SKIPPED_UNROUTED, c.status)
    }

    @Test
    fun `currency mismatch classifies Skipped SKIPPED_CURRENCY_MISMATCH`() = runTest {
        nextParsed = parsed(currency = "USD")
        val c = pipeline().classify("b", "s", 1L) as Classification.Skipped
        assertEquals(MessageStatus.SKIPPED_CURRENCY_MISMATCH, c.status)
    }

    @Test
    fun `already-POSTED import id classifies AlreadyPosted`() = runTest {
        nextParsed = parsed()
        val importId = mapper.importIdFor(nextParsed!!)
        logDao.upsert(
            ProcessedMessageEntity(
                importId = importId, sender = "s", bankName = "HDFC Bank", last4 = "1234",
                amount = BigDecimal("100.00"), currency = "INR", status = MessageStatus.POSTED, timestamp = 1L,
            ),
        )
        assertTrue(pipeline().classify("b", "s", 1L) is Classification.AlreadyPosted)
    }

    @Test
    fun `no token classifies Paused and latches postingPaused`() = runTest {
        val noToken = FakeTokenStore(null)
        val ps = FakePostingStateStore()
        val p = TransactionPipeline(
            smsParser = SmsParser { _, _, _ -> parsed() },
            mapper = mapper, resolver = resolver, poster = FakeTransactionPoster(),
            mappingRuleDao = ruleDao, processedMessageDao = logDao, tokenStore = noToken, postingState = ps,
        )
        val c = p.classify("b", "s", 1L) as Classification.Paused
        assertEquals(TransactionPipeline.ERROR_NO_TOKEN, c.error)
        assertTrue(ps.isPaused()) // latched so no 401 storm
    }

    @Test
    fun `valid token and route classifies Postable with mapped transaction`() = runTest {
        nextParsed = parsed()
        val c = pipeline().classify("b", "s", 1L) as Classification.Postable
        assertEquals("b1", c.rule.budgetId)
        assertEquals("a1", c.transaction.accountId)
        assertEquals(mapper.importIdFor(nextParsed!!), c.importId)
    }

    @Test
    fun `a preloaded rules snapshot routes without reading the rules table`() = runTest {
        // The snapshot, not the DAO, decides the route: the seeded DAO rule can't route
        // this message, yet it classifies Postable against the passed-in snapshot.
        nextParsed = parsed(bank = "ICICI Bank", last4 = "5678")
        val snapshot = listOf(
            MappingRule(bankName = "ICICI Bank", last4 = "5678", budgetId = "bSnap", accountId = "aSnap", currencyCode = "INR"),
        )
        val c = pipeline().classify("b", "s", 1L, rules = snapshot) as Classification.Postable
        assertEquals("bSnap", c.rule.budgetId)
        assertEquals(0, ruleDao.getAllCalls)
    }

    @Test
    fun `a null rules argument reads fresh rules from the DAO`() = runTest {
        nextParsed = parsed()
        pipeline().classify("b", "s", 1L)
        pipeline().classify("b", "s", 2L)
        assertEquals(2, ruleDao.getAllCalls) // real-time path stays fresh-read per message
    }
}
