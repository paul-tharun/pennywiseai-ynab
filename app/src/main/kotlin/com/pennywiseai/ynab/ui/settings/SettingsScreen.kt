package com.pennywiseai.ynab.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.ynab.ui.rules.RulesList
import com.pennywiseai.ynab.ui.theme.LocalStatusColors

@Composable
fun SettingsScreen(
    onAddRule: () -> Unit,
    onMapSuggestion: (bank: String, last4: String?) -> Unit,
    onTokenCleared: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    var replacing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadConnection() }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        item { SectionTitle("YNAB") }
        item {
            val info = connection
            if (info != null && !replacing) {
                ConnectedRow(
                    summary = "Connected · ${info.budgetCount} budgets · ${info.accountCount} accounts",
                    onRefresh = { viewModel.refresh() },
                    onReplace = { replacing = true },
                    onDisconnect = { viewModel.clearToken(); onTokenCleared() },
                )
            } else {
                // Not connected, or replacing a token: show the field directly.
                TokenEntry(viewModel)
                if (replacing) TextButton(onClick = { replacing = false }) { Text("Cancel") }
            }
        }

        item { Spacer(Modifier.height(24.dp)); HorizontalDivider() }

        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Routes", Modifier.weight(1f))
                TextButton(onClick = onAddRule) { Text("+ Add") }
            }
        }
        // Rules + "Needs routing" subheader live in RulesList (restyled in this task).
        item { RulesList(onMapSuggestion = onMapSuggestion) }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ConnectedRow(
    summary: String,
    onRefresh: () -> Unit,
    onReplace: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = LocalStatusColors.current.success,
        )
        Text(summary, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "YNAB options")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Refresh") }, onClick = { menu = false; onRefresh() })
            DropdownMenuItem(text = { Text("Replace token") }, onClick = { menu = false; onReplace() })
            DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; onDisconnect() })
        }
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
    // Clear the field only after a SUCCESSFUL save so a sensitive PAT doesn't linger in state;
    // preserve input on validation failure so the user isn't forced to retype a long token.
    LaunchedEffect(state) { if (state is TokenUiState.Saved) token = "" }
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
        is TokenUiState.Saved -> Text("Token valid · ${s.budgetCount} budgets, ${s.accountCount} accounts")
        is TokenUiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
        TokenUiState.Idle -> Unit
    }
}
