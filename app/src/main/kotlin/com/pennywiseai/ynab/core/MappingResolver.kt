package com.pennywiseai.ynab.core

import com.pennywiseai.ynab.core.model.MappingRule

/**
 * Resolves a parsed message's (bankName, last4) to a MappingRule.
 *
 * Precedence: an exact rule (same bank, non-null last4 equal by string to the
 * message's last4) wins over the bank wildcard (last4 == null). No match -> null
 * (the caller logs SKIPPED_UNROUTED). A null message last4 can only match a
 * wildcard.
 */
class MappingResolver {

    fun resolve(rules: List<MappingRule>, bankName: String, last4: String?): MappingRule? {
        val forBank = rules.filter { it.bankName == bankName }
        if (last4 != null) {
            forBank.firstOrNull { it.last4 == last4 }?.let { return it }
        }
        return forBank.firstOrNull { it.last4 == null }
    }
}
