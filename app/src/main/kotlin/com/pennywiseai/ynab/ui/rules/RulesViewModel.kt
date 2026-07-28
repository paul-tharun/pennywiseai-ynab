package com.pennywiseai.ynab.ui.rules

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.UnroutedSuggestion
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.local.dao.SnapshotDao
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity
import com.pennywiseai.ynab.data.mapper.toDomain
import com.pennywiseai.ynab.data.mapper.toEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A pending create/edit from the rule editor. `last4` null (or blank) = bank wildcard. */
data class RuleDraft(
    val bankName: String,
    val last4: String?,
    val budgetId: String,
    val accountId: String,
    val currencyCode: String,
    val editRuleId: Long?,
)

/** Outcome of [RulesViewModel.saveRule]. */
sealed interface SaveRuleResult {
    data object Saved : SaveRuleResult
    data object DuplicateRoute : SaveRuleResult
    data class Invalid(val message: String) : SaveRuleResult
}

@HiltViewModel
class RulesViewModel @Inject constructor(
    private val mappingRuleDao: MappingRuleDao,
    private val snapshotDao: SnapshotDao,
    private val processedMessageDao: ProcessedMessageDao,
    private val enqueuer: BackfillEnqueuer,
) : ViewModel() {

    val rules: StateFlow<List<MappingRule>> =
        mappingRuleDao.observeAll().map { list -> list.map { it.toDomain() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val suggestions: StateFlow<List<UnroutedSuggestion>> =
        processedMessageDao.observeUnroutedSuggestions(MessageStatus.SKIPPED_UNROUTED)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _budgets = MutableStateFlow<List<BudgetEntity>>(emptyList())
    val budgets: StateFlow<List<BudgetEntity>> = _budgets

    private val _accounts = MutableStateFlow<List<AccountEntity>>(emptyList())
    val accounts: StateFlow<List<AccountEntity>> = _accounts

    fun loadBudgets() = viewModelScope.launch { _budgets.value = snapshotDao.getBudgets() }

    fun loadAccounts(budgetId: String) =
        viewModelScope.launch { _accounts.value = snapshotDao.getOpenAccounts(budgetId) }

    /**
     * Insert or update a rule. The wildcard (draft.last4 null/blank) is normalised to the
     * domain's `null` and then encoded as WILDCARD_LAST4 ("") purely through toEntity().
     * A duplicate (bankName, last4) surfaces as DuplicateRoute — the DAO's ABORT raises
     * SQLiteConstraintException (see MappingRuleDao), our last-line integrity guard.
     */
    suspend fun saveRule(draft: RuleDraft): SaveRuleResult {
        if (draft.bankName.isBlank()) return SaveRuleResult.Invalid("Bank name required")
        if (draft.budgetId.isBlank() || draft.accountId.isBlank()) {
            return SaveRuleResult.Invalid("Pick a budget and account")
        }
        val rule = MappingRule(
            bankName = draft.bankName.trim(),
            last4 = draft.last4?.ifBlank { null },
            budgetId = draft.budgetId,
            accountId = draft.accountId,
            currencyCode = draft.currencyCode,
        )
        return try {
            if (draft.editRuleId != null) {
                mappingRuleDao.update(rule.toEntity(id = draft.editRuleId))
            } else {
                mappingRuleDao.insert(rule.toEntity())
            }
            SaveRuleResult.Saved
        } catch (_: SQLiteConstraintException) {
            SaveRuleResult.DuplicateRoute
        }
    }

    fun deleteRule(rule: MappingRule) = viewModelScope.launch {
        // getAll -> match by (bank, storage last4) to recover the row id for @Delete.
        val entity = mappingRuleDao.getAll().firstOrNull {
            it.bankName == rule.bankName && it.last4 == (rule.last4 ?: "")
        } ?: return@launch
        mappingRuleDao.delete(entity)
    }

    /**
     * Retroactive import for a newly-mapped bank: re-read the inbox from the earliest
     * SKIPPED_UNROUTED row of this bank to now, so the now-routed messages post. Idempotent
     * via import_id dedup (ADR-0005); a no-op when the bank has no unrouted rows.
     */
    fun retroImport(bankName: String) = viewModelScope.launch {
        val from = processedMessageDao
            .getEarliestTimestampByStatusAndBank(MessageStatus.SKIPPED_UNROUTED, bankName)
            ?: return@launch
        enqueuer.enqueue(from, System.currentTimeMillis() + 1)
    }
}
