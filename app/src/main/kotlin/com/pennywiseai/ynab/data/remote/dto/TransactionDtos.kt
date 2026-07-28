package com.pennywiseai.ynab.data.remote.dto

import com.pennywiseai.ynab.core.model.SaveTransaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for `POST /budgets/{id}/transactions`. The request body is always the
 * `transactions` array (bulk) — real-time sends one element, backfill sends a
 * chunk (ADR-0004). The response reports created `transaction_ids` and, crucially,
 * `duplicate_import_ids`: elements YNAB rejected as an already-present import_id.
 * Both default to empty so a partial/absent field never NPEs. The pipeline (Plan 4)
 * maps these to POSTED. Defaults make dedup the sole authority (ADR-0005).
 */
@Serializable
data class SaveTransactionsRequest(val transactions: List<SaveTransaction>)

@Serializable
data class SaveTransactionsResponse(val data: SaveTransactionsData)

@Serializable
data class SaveTransactionsData(
    @SerialName("transaction_ids") val transactionIds: List<String> = emptyList(),
    @SerialName("duplicate_import_ids") val duplicateImportIds: List<String> = emptyList(),
)
