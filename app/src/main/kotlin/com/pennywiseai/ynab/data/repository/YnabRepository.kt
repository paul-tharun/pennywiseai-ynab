package com.pennywiseai.ynab.data.repository

import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.dao.SnapshotDao
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.mapper.toDomain
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
    private val mappingRuleDao: MappingRuleDao,
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

        val brokenRules = revalidateRules()
        return SnapshotResult.Success(budgets.size, accounts.size, brokenRules)
    }

    /**
     * Re-check every rule against the just-persisted snapshot and persist the result.
     * A rule whose target account no longer exists is marked broken (durably, so the
     * pipeline fails fast). A rule that resolves again is unbroken and, if its budget
     * currency changed, currency-synced (design spec, Settings). Returns the broken
     * rules for SnapshotResult.Success.brokenRules.
     */
    private suspend fun revalidateRules(): List<MappingRule> {
        val broken = mutableListOf<MappingRule>()
        for (rule in mappingRuleDao.getAll()) {
            if (!snapshotDao.accountExists(rule.budgetId, rule.accountId)) {
                if (!rule.broken) mappingRuleDao.update(rule.copy(broken = true))
                broken += rule.copy(broken = true).toDomain()
            } else {
                val currency = snapshotDao.getBudgetCurrency(rule.budgetId)
                val syncCurrency = currency != null && currency != rule.currencyCode
                if (syncCurrency || rule.broken) {
                    mappingRuleDao.update(
                        rule.copy(
                            currencyCode = if (syncCurrency) currency!! else rule.currencyCode,
                            broken = false, // resolves again -> clear the flag
                        ),
                    )
                }
            }
        }
        return broken
    }

    /** Map a non-success HTTP status to a SnapshotResult, or null if successful. */
    private fun classify(response: Response<*>): SnapshotResult? = when {
        response.isSuccessful -> null
        response.code() == 401 -> SnapshotResult.Unauthorized
        else -> SnapshotResult.Error("HTTP ${response.code()}")
    }
}
