package com.pennywiseai.ynab.data.local

/**
 * A distinct (bankName, last4) combo seen in SKIPPED_UNROUTED log rows with no
 * covering rule — offered in settings as a one-tap route to create. last4 is
 * nullable (a message may carry no account tail).
 */
data class UnroutedSuggestion(
    val bankName: String,
    val last4: String?,
)
