package com.pennywiseai.ynab.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.ynab.ui.nav.Screen
import com.pennywiseai.ynab.ui.rules.RuleEditorScreen
import com.pennywiseai.ynab.ui.settings.SettingsViewModel
import com.pennywiseai.ynab.ui.settings.TokenEntry
import com.pennywiseai.ynab.ui.settings.TokenUiState

/**
 * First-run: one dense screen, no wizard. A three-item checklist (permissions, connect YNAB,
 * map first card — optional) fills in checkmarks as steps complete; the pinned "Start
 * capturing" CTA enables once a valid token exists (the hard gate). Step 3 is skippable —
 * unrouted suggestions catch new cards later.
 */
@Composable
fun OnboardingScreen(
    onRequestPermissions: () -> Unit,
    onComplete: () -> Unit,
    permissionsGranted: Boolean,
    settings: SettingsViewModel = hiltViewModel(),
) {
    val tokenState by settings.tokenState.collectAsStateWithLifecycle()
    val connected = tokenState is TokenUiState.Saved
    var mappedCard by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Set up pennywise-ynab", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Three quick steps and your bank texts start flowing into YNAB.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Column(
            Modifier.weight(1f).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        ) {
            ChecklistItem(done = permissionsGranted, title = "Allow reading bank texts") {
                Text(
                    "SMS + notifications. Messages are read on-device — nothing else leaves your phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onRequestPermissions) { Text("Allow") }
            }

            ChecklistItem(done = connected, title = "Connect YNAB") {
                TokenEntry(settings)
            }

            ChecklistItem(done = mappedCard, title = "Map your first card  ·  OPTIONAL") {
                if (showEditor) {
                    RuleEditorScreen(
                        args = Screen.RuleEditor(),
                        onDone = { mappedCard = true; showEditor = false },
                    )
                } else {
                    OutlinedButton(onClick = { showEditor = true }) { Text("Add a route") }
                }
            }
        }

        Button(
            onClick = onComplete,
            enabled = connected,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) { Text("Start capturing") }
    }
}

@Composable
private fun ChecklistItem(done: Boolean, title: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}
