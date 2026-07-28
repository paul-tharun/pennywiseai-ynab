package com.pennywiseai.ynab.ui.common

import com.pennywiseai.ynab.data.local.MessageStatus
import java.math.BigDecimal

/** Human-readable label for a processed-message status (the fixed 5-status set). */
fun statusLabel(status: MessageStatus): String = when (status) {
    MessageStatus.POSTED -> "Posted"
    MessageStatus.SKIPPED_UNROUTED -> "Unrouted"
    MessageStatus.SKIPPED_NON_TRANSACTION -> "Not a transaction"
    MessageStatus.SKIPPED_CURRENCY_MISMATCH -> "Currency mismatch"
    MessageStatus.FAILED -> "Failed"
}

/** "INR 1234.50" — currency code + the parsed decimal (no locale grouping; display-only). */
fun formatAmount(amount: BigDecimal, currency: String): String = "$currency ${amount.toPlainString()}"
