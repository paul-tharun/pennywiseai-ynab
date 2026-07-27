package com.pennywiseai.ynab.core

import com.pennywiseai.parser.core.TransactionType

/** The four TransactionTypes this app posts to YNAB (ADR-0002). */
fun TransactionType.isPostable(): Boolean = when (this) {
    TransactionType.INCOME,
    TransactionType.EXPENSE,
    TransactionType.CREDIT,
    TransactionType.INVESTMENT -> true
    TransactionType.TRANSFER,
    TransactionType.BALANCE_UPDATE -> false
}

/**
 * YNAB amount sign for a postable type (ADR-0002): INCOME is an inflow (+1);
 * EXPENSE / CREDIT / INVESTMENT are outflows (-1). The two non-postable types
 * are skipped upstream and must never reach this function.
 */
fun TransactionType.ynabSign(): Int = when (this) {
    TransactionType.INCOME -> 1
    TransactionType.EXPENSE,
    TransactionType.CREDIT,
    TransactionType.INVESTMENT -> -1
    TransactionType.TRANSFER,
    TransactionType.BALANCE_UPDATE ->
        throw IllegalArgumentException("Non-postable type $this has no YNAB sign")
}
