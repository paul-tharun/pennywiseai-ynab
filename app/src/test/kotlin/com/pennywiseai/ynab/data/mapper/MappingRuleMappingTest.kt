package com.pennywiseai.ynab.data.mapper

import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.data.local.entity.MappingRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappingRuleMappingTest {

    @Test
    fun `wildcard maps null last4 to empty string and back`() {
        val domain = MappingRule("HDFC Bank", null, "b1", "a1", "INR")
        val entity = domain.toEntity()
        assertEquals("", entity.last4)          // stored non-null so the unique index binds
        assertEquals(domain, entity.toDomain()) // "" -> null on the way out
    }

    @Test
    fun `exact last4 survives the round trip`() {
        val domain = MappingRule("HDFC Bank", "1234", "b1", "a1", "INR")
        assertEquals(domain, domain.toEntity().toDomain())
    }

    @Test
    fun `toDomain converts empty string last4 to null`() {
        val entity = MappingRuleEntity(
            id = 7, bankName = "ICICI Bank", last4 = "", budgetId = "b2", accountId = "a2", currencyCode = "INR",
        )
        assertNull(entity.toDomain().last4)
    }
}
