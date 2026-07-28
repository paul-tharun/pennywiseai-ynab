package com.pennywiseai.ynab.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for `GET /budgets`. YNAB wraps every payload in a top-level `data` object.
 * Only the fields the app needs are modeled; the client's Json is configured with
 * ignoreUnknownKeys = true so YNAB's many other fields are dropped silently.
 * `currency_format` is nullable defensively — a budget without one cannot be
 * routed (no currency for the mismatch guard) and is filtered out upstream.
 */
@Serializable
data class BudgetsResponse(val data: BudgetsData)

@Serializable
data class BudgetsData(val budgets: List<BudgetDto>)

@Serializable
data class BudgetDto(
    val id: String,
    val name: String,
    @SerialName("currency_format") val currencyFormat: CurrencyFormatDto? = null,
)

@Serializable
data class CurrencyFormatDto(
    @SerialName("iso_code") val isoCode: String,
)
