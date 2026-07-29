package com.pennywiseai.ynab.ui.rules

/**
 * The live-preview line for the route editor: "SBI ·7756 → Personal / Everyday (₹)", or
 * "SBI ·7756 → Ignored" when [ignored]. Returns null until bank (and, for a route, budget
 * and account) are chosen. Pure — unit-tested in RoutePreviewTest. Blank last4 => "any".
 */
fun routePreview(
    bank: String,
    last4: String?,
    budgetName: String?,
    accountName: String?,
    currency: String?,
    ignored: Boolean = false,
): String? {
    if (bank.isBlank()) return null
    val card = last4?.ifBlank { null } ?: "any"
    if (ignored) return "$bank ·$card → Ignored"
    if (budgetName == null || accountName == null) return null
    val cur = currency?.let { " ($it)" } ?: ""
    return "$bank ·$card → $budgetName / $accountName$cur"
}
