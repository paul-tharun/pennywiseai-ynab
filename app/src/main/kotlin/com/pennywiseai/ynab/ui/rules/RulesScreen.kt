package com.pennywiseai.ynab.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Rules + unrouted suggestions, embedded in the Settings hub (Task 7). Each rule row edits
 * (bank+last4 -> editor) and deletes; each suggestion offers a one-tap map that opens the
 * editor prefilled with the bank+last4. Signature is load-bearing: SettingsScreen calls it.
 */
@Composable
fun RulesList(
    onEditRule: (Long) -> Unit,
    onMapSuggestion: (bank: String, last4: String?) -> Unit,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        rules.forEach { rule ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "${rule.bankName} · ${rule.last4 ?: "any card"}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (rule.broken) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            "${rule.currencyCode}${if (rule.broken) " · broken" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton(onClick = { viewModel.deleteRule(rule) }) { Text("Delete") }
                        }
                    }
                }
            }
        }

        if (suggestions.isNotEmpty()) {
            Text(
                "Unrouted",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            suggestions.forEach { s ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("${s.bankName} · ${s.last4 ?: "any card"}", Modifier.fillMaxWidth())
                        TextButton(onClick = { onMapSuggestion(s.bankName, s.last4) }) { Text("Map") }
                    }
                }
            }
        }
    }
}
