package com.pennywiseai.ynab.data.repository

import com.pennywiseai.ynab.data.local.dao.SnapshotDao
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.remote.YnabApi
import com.pennywiseai.ynab.data.remote.toEntity
import com.pennywiseai.ynab.data.token.TokenStore
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates token validation + snapshot refresh (design spec, Settings). Writes
 * the snapshot ONLY through SnapshotDao.replaceSnapshot (its raw inserts cascade —
 * Plan 2 carry-forward). Rule re-validation is added in Task 5.
 */
@Singleton
class YnabRepository @Inject constructor(
    private val api: YnabApi,
    private val snapshotDao: SnapshotDao,
    private val tokenStore: TokenStore,
) {

    /** Store the PAT, then validate it + refresh the snapshot in one flow. */
    suspend fun saveTokenAndRefresh(token: String): SnapshotResult {
        tokenStore.setToken(token)
        return refreshSnapshot()
    }

    /** Re-pull budgets → accounts and atomically replace the local snapshot. */
    suspend fun refreshSnapshot(): SnapshotResult {
        val budgetsResponse = try {
            api.getBudgets()
        } catch (e: IOException) {
            return SnapshotResult.Error(e.message ?: "network error")
        }
        classify(budgetsResponse)?.let { return it }

        val budgets = budgetsResponse.body()?.data?.budgets.orEmpty()
            .mapNotNull { it.toEntity() } // drop currency-less budgets

        val accounts = mutableListOf<AccountEntity>()
        for (budget in budgets) {
            val accountsResponse = try {
                api.getAccounts(budget.id)
            } catch (e: IOException) {
                return SnapshotResult.Error(e.message ?: "network error")
            }
            classify(accountsResponse)?.let { return it }
            accountsResponse.body()?.data?.accounts.orEmpty()
                .forEach { accounts += it.toEntity(budget.id) }
        }

        snapshotDao.replaceSnapshot(budgets, accounts)

        // brokenRules is filled in Task 5; snapshot persistence stands alone here.
        return SnapshotResult.Success(budgets.size, accounts.size, brokenRules = emptyList())
    }

    /** Map a non-success HTTP status to a SnapshotResult, or null if successful. */
    private fun classify(response: Response<*>): SnapshotResult? = when {
        response.isSuccessful -> null
        response.code() == 401 -> SnapshotResult.Unauthorized
        else -> SnapshotResult.Error("HTTP ${response.code()}")
    }
}
