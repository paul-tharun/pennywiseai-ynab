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

    @Test
    fun `quickRangeMillis spans the last N days ending now`() {
        val now = 1_000_000_000L
        val day = 24L * 60 * 60 * 1000
        assertEquals(now - 7 * day to now, quickRangeMillis(now, 7))
        assertEquals(now - 30 * day to now, quickRangeMillis(now, 30))
        assertEquals(now - 90 * day to now, quickRangeMillis(now, 90))
    }
}
