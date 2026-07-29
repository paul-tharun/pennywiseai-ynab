package com.pennywiseai.ynab.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class TimeTextTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `under a minute reads just now`() {
        assertEquals("just now", relativeTime(nowMillis = 10_000, thenMillis = 10_000))
        assertEquals("just now", relativeTime(nowMillis = 59_000, thenMillis = 0))
    }

    @Test
    fun `minutes hours and days are singular and plural`() {
        assertEquals("1 minute ago", relativeTime(nowMillis = minute, thenMillis = 0))
        assertEquals("2 minutes ago", relativeTime(nowMillis = 2 * minute, thenMillis = 0))
        assertEquals("1 hour ago", relativeTime(nowMillis = hour, thenMillis = 0))
        assertEquals("3 hours ago", relativeTime(nowMillis = 3 * hour, thenMillis = 0))
        assertEquals("1 day ago", relativeTime(nowMillis = day, thenMillis = 0))
        assertEquals("5 days ago", relativeTime(nowMillis = 5 * day, thenMillis = 0))
    }

    @Test
    fun `absoluteTime renders wall-clock time in the given zone`() {
        // 1970-01-01T09:12:00 in UTC = 9*3600 + 12*60 = 33120 s = 33_120_000 ms.
        assertEquals("9:12 AM", absoluteTime(thenMillis = 33_120_000L, zone = ZoneId.of("UTC")))
    }
}
