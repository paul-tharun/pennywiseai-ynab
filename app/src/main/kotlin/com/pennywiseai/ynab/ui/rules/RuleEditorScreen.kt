package com.pennywiseai.ynab.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.pennywiseai.ynab.ui.nav.Screen
import kotlinx.coroutines.launch

@Composable
fun RuleEditorScreen(
    args: Screen.RuleEditor,
    onDone: () -> Unit,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val budgets by viewModel.budgets.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var bank by remember { mutableStateOf(args.prefillBank ?: "") }
    var last4 by remember { mutableStateOf(args.prefillLast4 ?: "") }
    var budgetId by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var offerImportFor by remember { mutableStateOf<String?>(null) } // bank name, set after save from a suggestion

    LaunchedEffect(Unit) { viewModel.loadBudgets() }

    val selectedBudget = budgets.firstOrNull { it.id == budgetId }
    val currency = selectedBudget?.currencyCode ?: ""

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Text("New route", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(bank, { bank = it }, label = { Text("Bank name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(last4, { last4 = it }, label = { Text("Last 4 (blank = any card)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item { Text("Budget", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
        items(budgets.size) { i ->
            val b = budgets[i]
            FilterChip(
                selected = budgetId == b.id,
                onClick = { budgetId = b.id; accountId = ""; viewModel.loadAccounts(b.id) },
                label = { Text("${b.name} (${b.currencyCode})") },
            )
        }
        if (budgetId.isNotBlank()) {
            item { Text("Account", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
            items(accounts.size) { i ->
                val a = accounts[i]
                FilterChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = { Text(a.name) })
            }
        }
        item {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                TextButton(onClick = onDone) { Text("Cancel") }
                Button(onClick = {
                    scope.launch {
                        val result = viewModel.saveRule(
                            RuleDraft(bank, last4, budgetId, accountId, currency, args.editRuleId),
                        )
                        when (result) {
                            SaveRuleResult.Saved ->
                                if (args.prefillBank != null) offerImportFor = bank else onDone()
                            SaveRuleResult.DuplicateRoute -> error = "A route for this bank + last4 already exists"
                            is SaveRuleResult.Invalid -> error = result.message
                        }
                    }
                }) { Text("Save") }
            }
        }
    }

    // After mapping a previously-unrouted bank, offer to import its past transactions.
    offerImportFor?.let { b ->
        AlertDialog(
            onDismissRequest = { offerImportFor = null; onDone() },
            title = { Text("Import past transactions?") },
            text = { Text("Re-scan the inbox for $b so already-received messages post now.") },
            confirmButton = { TextButton(onClick = { viewModel.retroImport(b); offerImportFor = null; onDone() }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { offerImportFor = null; onDone() }) { Text("Not now") } },
        )
    }
}
