package com.pennywiseai.ynab.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.ynab.data.token.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * First-run gate. `hasToken` decides onboarding vs. the main shell — a token is the one
 * hard prerequisite (rule creation reads the snapshot the token fetch populated). Re-checked
 * after onboarding completes and after a token save/clear in settings.
 */
@HiltViewModel
class AppGateViewModel @Inject constructor(
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _hasToken = MutableStateFlow<Boolean?>(null) // null = still checking
    val hasToken: StateFlow<Boolean?> = _hasToken.asStateFlow()

    init { recheck() }

    fun recheck() {
        viewModelScope.launch {
            _hasToken.value = withContext(Dispatchers.IO) { tokenStore.getToken() != null }
        }
    }
}
