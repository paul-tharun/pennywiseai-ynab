package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.data.remote.dto.AccountsResponse
import com.pennywiseai.ynab.data.remote.dto.BudgetsResponse
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsRequest
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The YNAB REST surface the app uses. Every call returns Response<T> so callers
 * can branch on the HTTP status (401 unauthorized, 404 missing budget/account,
 * 429 rate limit, 5xx) — status classification lives in the repository (snapshot)
 * and the pipeline (Plan 4, posting). Base URL is https://api.ynab.com/ so paths
 * carry the `v1/` prefix.
 */
interface YnabApi {

    @GET("v1/budgets")
    suspend fun getBudgets(): Response<BudgetsResponse>

    @GET("v1/budgets/{budgetId}/accounts")
    suspend fun getAccounts(@Path("budgetId") budgetId: String): Response<AccountsResponse>

    @POST("v1/budgets/{budgetId}/transactions")
    suspend fun postTransactions(
        @Path("budgetId") budgetId: String,
        @Body body: SaveTransactionsRequest,
    ): Response<SaveTransactionsResponse>
}
