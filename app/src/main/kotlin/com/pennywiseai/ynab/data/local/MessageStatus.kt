package com.pennywiseai.ynab.data.local

/**
 * The terminal outcome recorded for a processed message. Fixed 5-value set
 * (CONTEXT.md / design spec) — do not extend. Un-parseable SMS are dropped, not
 * logged, so there is no "unparsed" status.
 */
enum class MessageStatus {
    POSTED,
    SKIPPED_UNROUTED,
    SKIPPED_NON_TRANSACTION,
    SKIPPED_CURRENCY_MISMATCH,
    FAILED,
}
