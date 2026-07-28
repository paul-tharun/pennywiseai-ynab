package com.pennywiseai.ynab.ui.rules

import androidx.compose.runtime.Composable

// TEMPORARY STUB — replaced in Task 8 with the real RulesList (rules + unrouted
// suggestions backed by RulesViewModel). Embedded by SettingsScreen (Task 7) so the
// module compiles ahead of Task 8. Signature is load-bearing.
@Composable
fun RulesList(onEditRule: (Long) -> Unit, onMapSuggestion: (String, String?) -> Unit) {}
