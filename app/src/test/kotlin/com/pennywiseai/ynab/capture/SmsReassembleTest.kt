package com.pennywiseai.ynab.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsReassembleTest {

    @Test
    fun `single part is returned as-is`() {
        val raw = reassembleSms(listOf("Spent Rs 100 at Coffee"), "VM-HDFCBK", 1_753_000_000_000L)
        assertEquals(RawSms("VM-HDFCBK", "Spent Rs 100 at Coffee", 1_753_000_000_000L), raw)
    }

    @Test
    fun `multiple parts concatenate in order with no separator`() {
        val raw = reassembleSms(listOf("Spent Rs 100 ", "at Coffee ", "ref ABC123"), "VM-HDFCBK", 42L)
        assertEquals("Spent Rs 100 at Coffee ref ABC123", raw?.body)
    }

    @Test
    fun `empty parts returns null`() {
        assertNull(reassembleSms(emptyList(), "VM-HDFCBK", 1L))
    }

    @Test
    fun `blank sender returns null`() {
        assertNull(reassembleSms(listOf("body"), "   ", 1L))
        assertNull(reassembleSms(listOf("body"), null, 1L))
    }
}
