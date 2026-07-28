package com.pennywiseai.ynab.pipeline

import com.pennywiseai.ynab.core.model.SaveTransaction
import com.pennywiseai.ynab.data.remote.YnabApi
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsRequest
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * POSTs one or more transactions to a single budget and classifies the HTTP result.
 * Accepts a List so the same call serves a one-element real-time post and a bulk
 * backfill chunk (Plan 5) — both are the YNAB `transactions` array. Never logs or
 * returns the token; `error` strings carry only HTTP status, no request detail.
 */
interface TransactionPoster {
    suspend fun post(budgetId: String, transactions: List<SaveTransaction>): PostOutcome
}

@Singleton
class YnabTransactionPoster @Inject constructor(
    private val api: YnabApi,
) : TransactionPoster {

    override suspend fun post(budgetId: String, transactions: List<SaveTransaction>): PostOutcome {
        val response = try {
            api.postTransactions(budgetId, SaveTransactionsRequest(transactions))
        } catch (e: IOException) {
            // Offline / timeout / DNS — transient, retry under a network constraint.
            return PostOutcome.Failed(retryable = true, error = e.message ?: "network error")
        }

        if (response.isSuccessful) return PostOutcome.Posted

        return when (val code = response.code()) {
            401 -> PostOutcome.Unauthorized
            404 -> PostOutcome.RouteBroken
            400 -> PostOutcome.Failed(retryable = false, error = "HTTP 400 - malformed request")
            429 -> PostOutcome.Failed(retryable = true, error = "HTTP 429 - rate limited")
            in 500..599 -> PostOutcome.Failed(retryable = true, error = "HTTP $code - server error")
            else -> PostOutcome.Failed(retryable = false, error = "HTTP $code")
        }
    }
}
