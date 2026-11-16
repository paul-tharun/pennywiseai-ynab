package com.pennywiseai.ynab.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class InboxTimestampTest {

    @Test
    fun `uses the service-center send time so backfill matches the receiver`() {
        assertEquals(1_000L, inboxTimestamp(dateSent = 1_000L, date = 5_000L))
    }

    @Test
    fun `falls back to the receive time when the send time is missing`() {
        assertEquals(5_000L, inboxTimestamp(dateSent = 0L, date = 5_000L))
    }
}
