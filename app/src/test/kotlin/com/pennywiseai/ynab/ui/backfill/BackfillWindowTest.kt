package com.pennywiseai.ynab.ui.backfill

import org.junit.Assert.assertEquals
import org.junit.Test

class BackfillWindowTest {

    @Test
    fun `inclusiveEndMillis adds exactly one day`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(day, inclusiveEndMillis(0L))
        assertEquals(1_000L + day, inclusiveEndMillis(1_000L))
    }
}
