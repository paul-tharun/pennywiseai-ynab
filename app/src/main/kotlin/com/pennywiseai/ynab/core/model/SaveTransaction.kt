package com.pennywiseai.ynab.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One YNAB SaveTransaction (an element of the POST body's `transactions` array).
 * JSON field names match the YNAB API (snake_case) via @SerialName.
 * `amount` is in milliunits (YNAB is always x1000), signed.
 */
@Serializable
data class SaveTransaction(
    @SerialName("account_id") val accountId: String,
    val date: String, // yyyy-MM-dd
    val amount: Long,
    @SerialName("payee_name") val payeeName: String? = null,
    val memo: String? = null,
    @SerialName("import_id") val importId: String,
    val approved: Boolean = true,
    val cleared: String = "cleared",
)
