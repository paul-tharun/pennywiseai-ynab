package com.pennywiseai.ynab.ui.common

import com.pennywiseai.ynab.data.local.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class UiTextTest {

    @Test
    fun `statusLabel is human readable for every status`() {
        assertEquals("Posted", statusLabel(MessageStatus.POSTED))
        assertEquals("Unrouted", statusLabel(MessageStatus.SKIPPED_UNROUTED))
        assertEquals("Not a transaction", statusLabel(MessageStatus.SKIPPED_NON_TRANSACTION))
        assertEquals("Currency mismatch", statusLabel(MessageStatus.SKIPPED_CURRENCY_MISMATCH))
        assertEquals("Failed", statusLabel(MessageStatus.FAILED))
    }

    @Test
    fun `formatAmount prefixes the currency code`() {
        assertEquals("INR 1234.50", formatAmount(BigDecimal("1234.50"), "INR"))
    }
}
