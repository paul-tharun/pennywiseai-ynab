package com.pennywiseai.ynab.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Rules + unrouted suggestions, embedded in the Settings hub. One flat list: neutral grey
 * avatars, thin dividers, a broken-route hint line (not a loud badge). Signature is
 * load-bearing: SettingsScreen calls it.
 */
@Composable
fun RulesList(
    onMapSuggestion: (bank: String, last4: String?) -> Unit,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        rules.forEach { rule ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(rule.bankName)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        if (rule.ignored) {
                            "${rule.bankName} ·${rule.last4 ?: "any"} → Ignored"
                        } else {
                            "${rule.bankName} ·${rule.last4 ?: "any"} → ${rule.currencyCode}"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (rule.broken) {
                        Text(
                            "Target account was deleted · tap to fix",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { viewModel.deleteRule(rule) }) { Text("Delete") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        if (suggestions.isNotEmpty()) {
            Text(
                "NEEDS ROUTING",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            suggestions.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(s.bankName)
                    Text(
                        "${s.bankName} ·${s.last4 ?: "any"}",
                        Modifier.weight(1f).padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = { viewModel.ignoreSuggestion(s.bankName, s.last4) }) { Text("Ignore") }
                    TextButton(onClick = { onMapSuggestion(s.bankName, s.last4) }) { Text("Map →") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Neutral grey initial avatar — no per-bank color (color means "problem", not decoration). */
@Composable
private fun Avatar(name: String) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
