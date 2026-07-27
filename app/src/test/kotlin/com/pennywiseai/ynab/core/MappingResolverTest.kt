package com.pennywiseai.ynab.core

import com.pennywiseai.ynab.core.model.MappingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappingResolverTest {

    private val resolver = MappingResolver()
    private val exact = MappingRule("HDFC Bank", "1234", "b1", "a-exact", "INR")
    private val wildcard = MappingRule("HDFC Bank", null, "b1", "a-wild", "INR")
    private val otherBank = MappingRule("ICICI Bank", null, "b2", "a-icici", "INR")

    @Test
    fun `exact last4 wins over the bank wildcard`() {
        val r = resolver.resolve(listOf(wildcard, exact, otherBank), "HDFC Bank", "1234")
        assertEquals("a-exact", r!!.accountId)
    }

    @Test
    fun `falls back to the wildcard when no exact last4 matches`() {
        val r = resolver.resolve(listOf(wildcard, exact), "HDFC Bank", "9999")
        assertEquals("a-wild", r!!.accountId)
    }

    @Test
    fun `a null message last4 matches only the wildcard`() {
        val r = resolver.resolve(listOf(exact, wildcard), "HDFC Bank", null)
        assertEquals("a-wild", r!!.accountId)
    }

    @Test
    fun `no rule for the bank returns null`() {
        assertNull(resolver.resolve(listOf(exact, wildcard), "SBI", "1234"))
    }

    @Test
    fun `null last4 with only exact rules returns null`() {
        assertNull(resolver.resolve(listOf(exact), "HDFC Bank", null))
    }

    @Test
    fun `last4 matches by exact string equality`() {
        // "234" must not match a rule whose last4 is "1234"
        assertNull(resolver.resolve(listOf(exact), "HDFC Bank", "234"))
    }
}
