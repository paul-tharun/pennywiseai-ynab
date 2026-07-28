package com.pennywiseai.ynab.ui.rules

/**
 * The live-preview line for the route editor: "SBI ·7756 → Personal / Everyday (₹)".
 * Returns null until bank, budget, and account are all chosen (nothing to preview yet).
 * Pure — unit-tested in RoutePreviewTest. A blank last4 renders as "any" (bank wildcard).
 */
fun routePreview(
    bank: String,
    last4: String?,
    budgetName: String?,
    accountName: String?,
    currency: String?,
): String? {
    if (bank.isBlank() || budgetName == null || accountName == null) return null
    val card = last4?.ifBlank { null } ?: "any"
    val cur = currency?.let { " ($it)" } ?: ""
    return "$bank ·$card → $budgetName / $accountName$cur"
}
