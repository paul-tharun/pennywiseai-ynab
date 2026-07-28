package com.pennywiseai.ynab.ui.common

import com.pennywiseai.ynab.data.local.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusSemanticsTest {

    @Test
    fun `posted is success`() {
        assertEquals(SemanticColor.SUCCESS, semanticColor(MessageStatus.POSTED))
    }

    @Test
    fun `unrouted and currency-mismatch are both warnings`() {
        assertEquals(SemanticColor.WARNING, semanticColor(MessageStatus.SKIPPED_UNROUTED))
        assertEquals(SemanticColor.WARNING, semanticColor(MessageStatus.SKIPPED_CURRENCY_MISMATCH))
    }

    @Test
    fun `failed is error`() {
        assertEquals(SemanticColor.ERROR, semanticColor(MessageStatus.FAILED))
    }

    @Test
    fun `non-transaction noise is neutral`() {
        assertEquals(SemanticColor.NEUTRAL, semanticColor(MessageStatus.SKIPPED_NON_TRANSACTION))
    }
}
