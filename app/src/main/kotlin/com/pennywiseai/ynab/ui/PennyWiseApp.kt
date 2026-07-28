package com.pennywiseai.ynab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.pennywiseai.ynab.ui.backfill.BackfillScreen
import com.pennywiseai.ynab.ui.common.PausedBanner
import com.pennywiseai.ynab.ui.history.HistoryScreen
import com.pennywiseai.ynab.ui.nav.Screen
import com.pennywiseai.ynab.ui.onboarding.OnboardingScreen
import com.pennywiseai.ynab.ui.rules.RuleEditorScreen
import com.pennywiseai.ynab.ui.settings.SettingsScreen

/**
 * Root composable. Gates onboarding on token presence (AppGateViewModel), then hosts the
 * bottom-nav shell. Navigation is a two-level hand-rolled stack: a current [Screen.Tab]
 * plus an optional pushed [Screen] (the rule editor). BackHandler pops the pushed screen.
 */
@Composable
fun PennyWiseApp(
    onRequestPermissions: () -> Unit,
    gate: AppGateViewModel = hiltViewModel(),
) {
    val hasToken by gate.hasToken.collectAsStateWithLifecycle()

    when (hasToken) {
        null -> Unit // still checking — render nothing (a splash is YAGNI here)
        false -> OnboardingScreen(
            onRequestPermissions = onRequestPermissions,
            onComplete = { gate.recheck() },
        )
        true -> MainShell(onRequestPermissions = onRequestPermissions, onTokenCleared = { gate.recheck() })
    }
}

@Composable
private fun MainShell(onRequestPermissions: () -> Unit, onTokenCleared: () -> Unit) {
    var tab by remember { mutableStateOf<Screen.Tab>(Screen.History) }
    var pushed by remember { mutableStateOf<Screen?>(null) }

    BackHandler(enabled = pushed != null) { pushed = null }

    Scaffold(
        bottomBar = {
            if (pushed == null) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Screen.History,
                        onClick = { tab = Screen.History },
                        icon = { Icon(Icons.Filled.List, contentDescription = null) },
                        label = { Text("History") },
                    )
                    NavigationBarItem(
                        selected = tab == Screen.Backfill,
                        onClick = { tab = Screen.Backfill },
                        icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                        label = { Text("Import") },
                    )
                    NavigationBarItem(
                        selected = tab == Screen.Settings,
                        onClick = { tab = Screen.Settings },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PausedBanner()
            when (val current = pushed) {
                is Screen.RuleEditor -> RuleEditorScreen(
                    args = current,
                    onDone = { pushed = null },
                )
                else -> when (tab) {
                    Screen.History -> HistoryScreen(
                        onMapRoute = { bank, last4 ->
                            pushed = Screen.RuleEditor(prefillBank = bank, prefillLast4 = last4)
                        },
                    )
                    Screen.Backfill -> BackfillScreen()
                    Screen.Settings -> SettingsScreen(
                        onAddRule = { pushed = Screen.RuleEditor() },
                        onEditRule = { id -> pushed = Screen.RuleEditor(editRuleId = id) },
                        onMapSuggestion = { bank, last4 ->
                            pushed = Screen.RuleEditor(prefillBank = bank, prefillLast4 = last4)
                        },
                        onTokenCleared = onTokenCleared,
                    )
                }
            }
        }
    }
}
