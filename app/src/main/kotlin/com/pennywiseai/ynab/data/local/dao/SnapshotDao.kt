package com.pennywiseai.ynab.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity

@Dao
interface SnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudgets(budgets: List<BudgetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(accounts: List<AccountEntity>)

    /** Deleting budgets cascades to their accounts (FK ON DELETE CASCADE). */
    @Query("DELETE FROM budgets")
    suspend fun clearBudgets()

    /** Atomically replace the whole snapshot tree — a token save / manual refresh re-pulls it. */
    @Transaction
    suspend fun replaceSnapshot(budgets: List<BudgetEntity>, accounts: List<AccountEntity>) {
        clearBudgets()
        insertBudgets(budgets)
        insertAccounts(accounts)
    }

    @Query("SELECT * FROM budgets ORDER BY name")
    suspend fun getBudgets(): List<BudgetEntity>

    @Query("SELECT * FROM accounts WHERE budgetId = :budgetId AND closed = 0 AND deleted = 0 ORDER BY name")
    suspend fun getOpenAccounts(budgetId: String): List<AccountEntity>

    @Query("SELECT currencyCode FROM budgets WHERE id = :budgetId")
    suspend fun getBudgetCurrency(budgetId: String): String?

    @Query("SELECT EXISTS(SELECT 1 FROM accounts WHERE id = :accountId AND budgetId = :budgetId)")
    suspend fun accountExists(budgetId: String, accountId: String): Boolean
}
