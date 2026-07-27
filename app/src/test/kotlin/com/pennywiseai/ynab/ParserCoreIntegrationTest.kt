package com.pennywiseai.ynab

import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.parser.core.bank.BankParserFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the pinned parser-core submodule is on the app's classpath and callable
 * via the content-aware factory entry point. The SMS below is a verified case
 * from parser-core's own HDFC test suite (UPI debit).
 */
class ParserCoreIntegrationTest {

    private val hdfcUpiDebit =
        "Rs.500.00 debited from A/c XX1234 on 20-Oct-25 to merchant@upi (UPI Ref No 123456789012)"

    @Test
    fun `factory parses a real HDFC expense SMS`() {
        val parsed = BankParserFactory.parse(hdfcUpiDebit, "CP-HDFCBK-S", 1_690_000_000_000L)

        assertNotNull("HDFC UPI debit should parse", parsed)
        assertEquals(TransactionType.EXPENSE, parsed!!.type)
        assertEquals("1234", parsed.accountLast4)
        assertEquals("123456789012", parsed.reference)
    }

    @Test
    fun `factory returns null for a non-bank message`() {
        val parsed = BankParserFactory.parse("Hey, are we still on for dinner?", "AD-FRIEND", 0L)
        assertNull(parsed)
    }

    @Test
    fun `generateTransactionId is a 32-char hex, stable across timestamps`() {
        val a = BankParserFactory.parse(hdfcUpiDebit, "CP-HDFCBK-S", 111L)!!.generateTransactionId()
        val b = BankParserFactory.parse(hdfcUpiDebit, "CP-HDFCBK-S", 999L)!!.generateTransactionId()

        assertEquals(32, a.length)
        assertEquals(a, b) // ADR-0001: id excludes the timestamp
        assertTrue(a.all { it in "0123456789abcdef" })
    }
}
