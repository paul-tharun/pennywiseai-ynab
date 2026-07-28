package com.pennywiseai.ynab.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.ynab.core.model.MappingRule
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.dao.MappingRuleDao
import com.pennywiseai.ynab.data.local.dao.ProcessedMessageDao
import com.pennywiseai.ynab.data.mapper.toDomain
import com.pennywiseai.ynab.data.repository.SnapshotResult
import com.pennywiseai.ynab.data.repository.YnabRepository
import com.pennywiseai.ynab.data.state.PostingStateStore
import com.pennywiseai.ynab.data.token.TokenStore
import com.pennywiseai.ynab.ui.rules.BackfillEnqueuer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface TokenUiState {
    data object Idle : TokenUiState
    data object Saving : TokenUiState
    data class Saved(val budgetCount: Int, val accountCount: Int) : TokenUiState
    data class Error(val message: String) : TokenUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: YnabRepository,
    private val tokenStore: TokenStore,
    private val postingState: PostingStateStore,
    private val mappingRuleDao: MappingRuleDao,
    private val processedMessageDao: ProcessedMessageDao,
    private val enqueuer: BackfillEnqueuer,
) : ViewModel() {

    val paused: StateFlow<Boolean> =
        postingState.observePaused().stateIn(viewModelScope, SharingStarted.Eagerly, postingState.isPaused())

    val brokenRules: StateFlow<List<MappingRule>> =
        mappingRuleDao.observeAll()
            .map { rules -> rules.filter { it.broken }.map { it.toDomain() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tokenState = MutableStateFlow<TokenUiState>(TokenUiState.Idle)
    val tokenState: StateFlow<TokenUiState> = _tokenState

    fun saveToken(token: String) {
        if (token.isBlank()) { _tokenState.value = TokenUiState.Error("Token can't be empty"); return }
        _tokenState.value = TokenUiState.Saving
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) { repository.saveTokenAndRefresh(token.trim()) }) {
                is SnapshotResult.Success -> {
                    _tokenState.value = TokenUiState.Saved(result.budgetCount, result.accountCount)
                    retryFailedFrom(System.currentTimeMillis()) // spec: bulk-retry every FAILED on validated save
                }
                SnapshotResult.Unauthorized -> _tokenState.value = TokenUiState.Error("Token rejected by YNAB (401)")
                is SnapshotResult.Error -> _tokenState.value = TokenUiState.Error(result.message)
            }
        }
    }

    fun refresh() {
        _tokenState.value = TokenUiState.Saving
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) { repository.refreshSnapshot() }) {
                is SnapshotResult.Success -> _tokenState.value = TokenUiState.Saved(result.budgetCount, result.accountCount)
                SnapshotResult.Unauthorized -> _tokenState.value = TokenUiState.Error("Token rejected by YNAB (401)")
                is SnapshotResult.Error -> _tokenState.value = TokenUiState.Error(result.message)
            }
        }
    }

    fun clearToken() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { tokenStore.clear() }
            postingState.setPaused(true) // no token -> paused (design spec, Error handling)
            _tokenState.value = TokenUiState.Idle
        }
    }

    /**
     * Re-drive every FAILED message from the earliest FAILED timestamp to [now]+1 (idempotent
     * via import_id, ADR-0005). No-op when nothing has FAILED. `internal` so it is unit-tested
     * against the real DB without going through the async saveToken flow.
     */
    internal suspend fun retryFailedFrom(now: Long) {
        val from = processedMessageDao.getEarliestTimestampByStatus(MessageStatus.FAILED) ?: return
        enqueuer.enqueue(from, now + 1)
    }
}
