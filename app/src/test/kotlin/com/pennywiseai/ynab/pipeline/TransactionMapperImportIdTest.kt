package com.pennywiseai.ynab.pipeline

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.ynab.core.TransactionMapper
import com.pennywiseai.ynab.core.model.MappingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.math.BigDecimal
import org.junit.Test
import java.time.ZoneId

class TransactionMapperImportIdTest {

    private val mapper = TransactionMapper(ZoneId.of("UTC"))

    private fun parsed(type: TransactionType = TransactionType.EXPENSE) = ParsedTransaction(
        amount = BigDecimal("100.00"), type = type, merchant = "Coffee", reference = "ref1",
        accountLast4 = "1234", balance = null, smsBody = "spent Rs 100 at Coffee ref1",
        sender = "VM-HDFCBK", timestamp = 1_753_000_000_000L, bankName = "HDFC Bank",
    )

    @Test
    fun `importIdFor is the PW prefixed content id`() {
        val p = parsed()
        assertEquals(TransactionMapper.IMPORT_ID_PREFIX + p.generateTransactionId(), mapper.importIdFor(p))
    }

    @Test
    fun `importIdFor matches the id map embeds`() {
        val p = parsed()
        val rule = MappingRule("HDFC Bank", "1234", "b1", "a1", "INR")
        assertEquals(mapper.importIdFor(p), mapper.map(p, rule).importId) // skip-path id == post-path id
    }

    @Test
    fun `importId is stable and within YNAB's 36-char limit`() {
        val p = parsed()
        assertEquals(mapper.importIdFor(p), mapper.importIdFor(p)) // deterministic
        assertTrue(mapper.importIdFor(p).length <= 36)
    }

    @Test
    fun `importIdFor works for a non-postable type (no rule needed)`() {
        // TRANSFER never reaches map(), but the pipeline still logs it and needs a PK.
        val p = parsed(TransactionType.TRANSFER)
        assertTrue(mapper.importIdFor(p).startsWith("PW:"))
    }
}
