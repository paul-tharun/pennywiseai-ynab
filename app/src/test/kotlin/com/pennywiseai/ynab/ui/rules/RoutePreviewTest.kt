package com.pennywiseai.ynab.ui.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutePreviewTest {

    @Test
    fun `full selection renders the route line`() {
        assertEquals(
            "SBI ·7756 → Personal / Everyday (₹)",
            routePreview("SBI", "7756", "Personal", "Everyday", "₹"),
        )
    }

    @Test
    fun `blank last4 shows any card`() {
        assertEquals(
            "SBI ·any → Personal / Everyday (INR)",
            routePreview("SBI", null, "Personal", "Everyday", "INR"),
        )
    }

    @Test
    fun `incomplete selection has no preview`() {
        assertNull(routePreview("", null, "Personal", "Everyday", "INR")) // no bank
        assertNull(routePreview("SBI", "7756", null, "Everyday", "INR")) // no budget
        assertNull(routePreview("SBI", "7756", "Personal", null, "INR")) // no account
    }

    @Test
    fun `ignored preview shows the card routed to Ignored`() {
        assertEquals("SBI ·7756 → Ignored",
            routePreview("SBI", "7756", null, null, null, ignored = true))
    }

    @Test
    fun `ignored preview renders a blank last4 as any`() {
        assertEquals("SBI ·any → Ignored",
            routePreview("SBI", "", null, null, null, ignored = true))
    }

    @Test
    fun `ignored preview is null when the bank is blank`() {
        assertNull(routePreview("", "7756", null, null, null, ignored = true))
    }
}
