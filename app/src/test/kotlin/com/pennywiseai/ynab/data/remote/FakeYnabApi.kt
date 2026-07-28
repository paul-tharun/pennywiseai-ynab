package com.pennywiseai.ynab.data.remote

import com.pennywiseai.ynab.data.remote.dto.AccountsData
import com.pennywiseai.ynab.data.remote.dto.AccountsResponse
import com.pennywiseai.ynab.data.remote.dto.BudgetsData
import com.pennywiseai.ynab.data.remote.dto.BudgetsResponse
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsRequest
import com.pennywiseai.ynab.data.remote.dto.SaveTransactionsResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * Configurable YnabApi double for repository tests. Lambdas let a test return a
 * canned body, an error code, or throw IOException (offline). accountsByBudget maps
 * a budget id to its accounts response.
 */
class FakeYnabApi : YnabApi {

    var budgets: () -> Response<BudgetsResponse> =
        { Response.success(BudgetsResponse(BudgetsData(emptyList()))) }

    var accountsByBudget: (String) -> Response<AccountsResponse> =
        { Response.success(AccountsResponse(AccountsData(emptyList()))) }

    override suspend fun getBudgets(): Response<BudgetsResponse> = budgets()

    override suspend fun getAccounts(budgetId: String): Response<AccountsResponse> =
        accountsByBudget(budgetId)

    override suspend fun postTransactions(
        budgetId: String,
        body: SaveTransactionsRequest,
    ): Response<SaveTransactionsResponse> = throw UnsupportedOperationException("not used in Plan 3")

    companion object {
        /** Build a Retrofit-style error Response for a given HTTP code. */
        fun <T> error(code: Int): Response<T> =
            Response.error(code, "{}".toResponseBody("application/json".toMediaType()))
    }
}
