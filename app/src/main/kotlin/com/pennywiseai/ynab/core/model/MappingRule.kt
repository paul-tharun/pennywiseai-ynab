package com.pennywiseai.ynab.core.model

/**
 * A user-defined route: (bankName, last4?) -> (budgetId, accountId).
 * last4 == null is a bank-wide wildcard. currencyCode is the target budget's
 * ISO currency, cached for the offline currency-mismatch guard (used in Plan 3).
 */
data class MappingRule(
    val bankName: String,
    val last4: String?,
    val budgetId: String,
    val accountId: String,
    val currencyCode: String,
    /** True when the rule's target budget/account no longer resolves in the snapshot;
     *  its messages log SKIPPED_UNROUTED (design spec, Settings) until remapped. */
    val broken: Boolean = false,
)
