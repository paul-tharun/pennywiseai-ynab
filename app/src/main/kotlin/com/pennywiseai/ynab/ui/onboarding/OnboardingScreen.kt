package com.pennywiseai.ynab.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.pennywiseai.ynab.ui.nav.Screen
import com.pennywiseai.ynab.ui.rules.RuleEditorScreen
import com.pennywiseai.ynab.ui.settings.SettingsViewModel
import com.pennywiseai.ynab.ui.settings.TokenUiState

/**
 * First-run sequence (design spec, Onboarding): grant SMS permissions -> enter+validate
 * token (populates the snapshot) -> create the first rule -> done (backfill is offered from
 * the rule editor's retroactive-import path or the Import tab). Token save is the hard gate:
 * onComplete flips the app to the main shell only after a valid token exists.
 */
@Composable
fun OnboardingScreen(
    onRequestPermissions: () -> Unit,
    onComplete: () -> Unit,
    settings: SettingsViewModel = hiltViewModel(),
) {
    var step by remember { mutableIntStateOf(0) }
    val tokenState by settings.tokenState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Welcome to PennyWise → YNAB", style = MaterialTheme.typography.headlineSmall)
        when (step) {
            0 -> {
                Text("Grant SMS + notification permissions so the app can read bank messages.")
                Button(onClick = { onRequestPermissions(); step = 1 }) { Text("Grant permissions") }
                TextButton(onClick = { step = 1 }) { Text("Skip for now") }
            }
            1 -> {
                Text("Enter your YNAB Personal Access Token.")
                com.pennywiseai.ynab.ui.settings.TokenEntry(settings)
                if (tokenState is TokenUiState.Saved) {
                    Button(onClick = { step = 2 }) { Text("Next: add a route") }
                }
            }
            2 -> {
                Text("Map your first bank card to a YNAB account.")
                RuleEditorScreen(args = Screen.RuleEditor(), onDone = onComplete)
            }
        }
    }
}
