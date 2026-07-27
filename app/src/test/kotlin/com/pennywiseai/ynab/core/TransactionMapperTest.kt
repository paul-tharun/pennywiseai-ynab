package com.pennywiseai.ynab.core

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.ynab.core.model.MappingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId

class TransactionMapperTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val mapper = TransactionMapper(zone)
    private val rule = MappingRule("HDFC Bank", "1234", "budget-1", "account-1", "INR")

    private fun parsed(
        amount: BigDecimal,
        type: TransactionType,
        merchant: String? = "Amazon",
        reference: String? = "REF123",
        smsBody: String = "spent Rs 100 at Amazon ref REF123",
        sender: String = "VM-HDFCBK",
        timestamp: Long = 1_690_000_000_000L,
    ) = ParsedTransaction(
        amount = amount,
        type = type,
        merchant = merchant,
        reference = reference,
        accountLast4 = "1234",
        balance = null,
        smsBody = smsBody,
        sender = sender,
        timestamp = timestamp,
        bankName = "HDFC Bank",
    )

    @Test
    fun `expense maps to negative milliunits`() {
        val tx = mapper.map(parsed(BigDecimal("100.00"), TransactionType.EXPENSE), rule)
        assertEquals(-100_000L, tx.amount)
    }

    @Test
    fun `income maps to positive milliunits`() {
        val tx = mapper.map(parsed(BigDecimal("2500.50"), TransactionType.INCOME), rule)
        assertEquals(2_500_500L, tx.amount)
    }

    @Test
    fun `credit and investment are outflows`() {
        assertTrue(mapper.map(parsed(BigDecimal("10"), TransactionType.CREDIT), rule).amount < 0)
        assertTrue(mapper.map(parsed(BigDecimal("10"), TransactionType.INVESTMENT), rule).amount < 0)
    }

    @Test
    fun `milliunit rounding is half-up`() {
        // 100.4565 -> x1000 = 100456.5 -> HALF_UP -> 100457
        val tx = mapper.map(parsed(BigDecimal("100.4565"), TransactionType.EXPENSE), rule)
        assertEquals(-100_457L, tx.amount)
    }

    @Test
    fun `account_id comes from the rule`() {
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE), rule)
        assertEquals("account-1", tx.accountId)
    }

    @Test
    fun `date is yyyy-MM-dd in the given zone`() {
        // 1_690_000_000_000 ms = 2023-07-22T04:26:40Z; in Asia/Kolkata still 2023-07-22
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE), rule)
        assertEquals("2023-07-22", tx.date)
    }

    @Test
    fun `import_id is PW-prefixed and within 36 chars`() {
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE), rule)
        assertTrue(tx.importId.startsWith("PW:"))
        assertEquals(35, tx.importId.length)
        assertTrue(tx.importId.length <= 36)
    }

    @Test
    fun `import_id ignores the timestamp so both capture paths agree`() {
        val a = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE, timestamp = 111L), rule)
        val b = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE, timestamp = 999L), rule)
        assertEquals(a.importId, b.importId)
    }

    @Test
    fun `payee is truncated to 50 chars`() {
        val long = "X".repeat(80)
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE, merchant = long), rule)
        assertEquals(50, tx.payeeName!!.length)
    }

    @Test
    fun `blank merchant omits payee`() {
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE, merchant = "   "), rule)
        assertNull(tx.payeeName)
    }

    @Test
    fun `null merchant omits payee`() {
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE, merchant = null), rule)
        assertNull(tx.payeeName)
    }

    @Test
    fun `memo is truncated to 200 chars`() {
        val long = "Y".repeat(250)
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE, reference = long), rule)
        assertEquals(200, tx.memo!!.length)
    }

    @Test
    fun `approved is true and cleared is cleared`() {
        val tx = mapper.map(parsed(BigDecimal("1"), TransactionType.EXPENSE), rule)
        assertTrue(tx.approved)
        assertEquals("cleared", tx.cleared)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-postable type is rejected`() {
        mapper.map(parsed(BigDecimal.ZERO, TransactionType.BALANCE_UPDATE), rule)
    }
}
