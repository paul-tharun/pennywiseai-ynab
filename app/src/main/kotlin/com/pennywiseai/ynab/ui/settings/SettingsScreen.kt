package com.pennywiseai.ynab.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// TEMPORARY STUB — replaced by the real implementation in Task 7.
// Signature is load-bearing: PennyWiseApp (Task 6) calls it with these exact params.
@Composable
fun SettingsScreen(
    onAddRule: () -> Unit,
    onEditRule: (Long) -> Unit,
    onMapSuggestion: (String, String?) -> Unit,
    onTokenCleared: () -> Unit,
) {
    Text("Settings")
}
