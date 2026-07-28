package com.pennywiseai.ynab.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

// TEMPORARY STUB — replaced by the real implementation in Task 7.
// Exposes just enough (`val paused: StateFlow<Boolean>`) for the REAL PausedBanner
// (Task 6) to compile and resolve `hiltViewModel()`. Task 7 replaces this file with the
// full settings VM, keeping a `paused` field of the same name so PausedBanner needs no change.
@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {
    val paused: StateFlow<Boolean> = MutableStateFlow(false)
}
