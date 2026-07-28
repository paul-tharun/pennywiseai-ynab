package com.pennywiseai.ynab.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.pennywiseai.ynab.ui.rules.RulesList

@Composable
fun SettingsScreen(
    onAddRule: () -> Unit,
    onEditRule: (Long) -> Unit,
    onMapSuggestion: (bank: String, last4: String?) -> Unit,
    onTokenCleared: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val brokenRules by viewModel.brokenRules.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Text("YNAB token", style = MaterialTheme.typography.titleMedium)
            // Token field + save/validate + state display are shared with onboarding via TokenEntry (DRY).
            TokenEntry(viewModel)
            Row {
                OutlinedButton(onClick = { viewModel.refresh() }) { Text("Refresh") }
                TextButton(onClick = { viewModel.clearToken(); onTokenCleared() }) { Text("Clear") }
            }
        }

        if (brokenRules.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "${brokenRules.size} broken route(s)",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text("Their target budget/account no longer exists. Edit or delete them below.")
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Mapping rules", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onAddRule) { Text("Add") }
            }
        }

        // Rules list + unrouted suggestions are rendered by RulesList (Task 8), which
        // reads its own RulesViewModel. It is embedded here so Settings is the one hub.
        item { RulesList(onEditRule = onEditRule, onMapSuggestion = onMapSuggestion) }
    }
}

/**
 * Reusable token entry: password field + save/validate button + inline validation state.
 * Shared by [SettingsScreen] and the onboarding token step so the field/state live in one place.
 */
@Composable
fun TokenEntry(viewModel: SettingsViewModel) {
    var token by remember { mutableStateOf("") }
    val state by viewModel.tokenState.collectAsStateWithLifecycle()
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("Personal Access Token") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { viewModel.saveToken(token) }) { Text("Save & validate") }
    when (val s = state) {
        is TokenUiState.Saving -> Text("Validating…")
        is TokenUiState.Saved -> Text("Token valid · ${s.budgetCount} budgets")
        is TokenUiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
        TokenUiState.Idle -> Unit
    }
}
