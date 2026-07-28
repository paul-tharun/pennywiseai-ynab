package com.pennywiseai.ynab.capture

import com.pennywiseai.parser.core.bank.BankParserFactory
import com.pennywiseai.ynab.pipeline.PipelineModule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the REAL production binding (PipelineModule.provideSenderFilter, backed by
 * parser-core's BankParserFactory) — no fake — because the pre-filter's whole safety
 * argument is that it agrees with the parser the worker will run. Each negative case
 * therefore also asserts that BankParserFactory.parse returns null for that sender even
 * when handed a genuine bank SMS body: skipping the enqueue drops nothing.
 */
class SenderFilterTest {

    private val filter = PipelineModule.provideSenderFilter()

    /** A verified HDFC UPI debit from parser-core's own test suite. */
    private val hdfcUpiDebit =
        "Rs.500.00 debited from A/c XX1234 on 20-Oct-25 to merchant@upi (UPI Ref No 123456789012)"

    private val timestamp = 1_753_000_000_000L

    @Test
    fun `admits a bank DLT sender that parser-core actually parses`() {
        assertTrue(filter.mightBeBank("CP-HDFCBK-S"))
        assertNotNull(
            "filter must admit every sender the pipeline can parse",
            BankParserFactory.parse(hdfcUpiDebit, "CP-HDFCBK-S", timestamp),
        )
    }

    @Test
    fun `admits the sender carried through multipart reassembly`() {
        val raw = reassembleSms(
            bodies = listOf("Rs.500.00 debited from A/c XX1234 on 20-Oct-25 ", "to merchant@upi (UPI Ref No 123456789012)"),
            sender = "VM-HDFCBK-S",
            timestamp = timestamp,
        )

        assertNotNull(raw)
        assertTrue(filter.mightBeBank(raw!!.sender))
        assertNotNull(BankParserFactory.parse(raw.body, raw.sender, raw.timestamp))
    }

    @Test
    fun `rejects a personal phone number, which no parser could have parsed`() {
        assertFalse(filter.mightBeBank("+15551234567"))
        assertNull(BankParserFactory.parse(hdfcUpiDebit, "+15551234567", timestamp))
    }

    @Test
    fun `rejects a personal alphanumeric sender, which no parser could have parsed`() {
        assertFalse(filter.mightBeBank("AD-FRIEND"))
        assertNull(BankParserFactory.parse(hdfcUpiDebit, "AD-FRIEND", timestamp))
    }

    @Test
    fun `rejects a promotional sender, which no parser could have parsed`() {
        assertFalse(filter.mightBeBank("PIZZAHUT"))
        assertNull(BankParserFactory.parse(hdfcUpiDebit, "PIZZAHUT", timestamp))
    }

    @Test
    fun `blank sender is rejected rather than crashing`() {
        // reassembleSms already drops null/blank senders, so the receiver never asks;
        // assert the seam is total anyway (BankParserFactory just matches nothing).
        assertFalse(filter.mightBeBank(""))
        assertFalse(filter.mightBeBank("   "))
        assertNull(reassembleSms(listOf(hdfcUpiDebit), null, timestamp))
    }
}
