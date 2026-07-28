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
    val tokenState by viewModel.tokenState.collectAsStateWithLifecycle()
    val brokenRules by viewModel.brokenRules.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Text("YNAB token", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Personal Access Token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                Button(onClick = { viewModel.saveToken(token); token = "" }) { Text("Save & validate") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { viewModel.refresh() }) { Text("Refresh") }
                TextButton(onClick = { viewModel.clearToken(); onTokenCleared() }) { Text("Clear") }
            }
            when (val s = tokenState) {
                is TokenUiState.Saving -> Text("Validating…")
                is TokenUiState.Saved -> Text("Saved · ${s.budgetCount} budgets, ${s.accountCount} accounts")
                is TokenUiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                TokenUiState.Idle -> Unit
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
