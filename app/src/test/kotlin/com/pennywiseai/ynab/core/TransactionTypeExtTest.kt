package com.pennywiseai.ynab.core

import com.pennywiseai.parser.core.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionTypeExtTest {

    @Test
    fun `the four postable types are postable`() {
        listOf(
            TransactionType.INCOME,
            TransactionType.EXPENSE,
            TransactionType.CREDIT,
            TransactionType.INVESTMENT,
        ).forEach { assertTrue("$it should be postable", it.isPostable()) }
    }

    @Test
    fun `transfer and balance update are not postable`() {
        assertFalse(TransactionType.TRANSFER.isPostable())
        assertFalse(TransactionType.BALANCE_UPDATE.isPostable())
    }

    @Test
    fun `income is an inflow`() {
        assertEquals(1, TransactionType.INCOME.ynabSign())
    }

    @Test
    fun `expense, credit, investment are outflows`() {
        assertEquals(-1, TransactionType.EXPENSE.ynabSign())
        assertEquals(-1, TransactionType.CREDIT.ynabSign())
        assertEquals(-1, TransactionType.INVESTMENT.ynabSign())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `transfer has no sign`() {
        TransactionType.TRANSFER.ynabSign()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `balance update has no sign`() {
        TransactionType.BALANCE_UPDATE.ynabSign()
    }
}
