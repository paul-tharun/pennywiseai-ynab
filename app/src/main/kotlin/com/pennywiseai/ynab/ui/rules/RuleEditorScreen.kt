package com.pennywiseai.ynab.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.ynab.data.local.entity.AccountEntity
import com.pennywiseai.ynab.data.local.entity.BudgetEntity
import com.pennywiseai.ynab.ui.nav.Screen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    var offerImportFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.loadBudgets() }

    val selectedBudget = budgets.firstOrNull { it.id == budgetId }
    val selectedAccount = accounts.firstOrNull { it.id == accountId }
    val currency = selectedBudget?.currencyCode ?: ""
    val valid = bank.isNotBlank() && budgetId.isNotBlank() && accountId.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (args.editRuleId == null) "New route" else "Edit route") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        enabled = valid,
                        onClick = {
                            scope.launch {
                                when (val result = viewModel.saveRule(
                                    RuleDraft(bank, last4, budgetId, accountId, currency, args.editRuleId),
                                )) {
                                    SaveRuleResult.Saved ->
                                        if (args.prefillBank != null) offerImportFor = bank else onDone()
                                    SaveRuleResult.DuplicateRoute ->
                                        error = "A route for this bank + last4 already exists"
                                    is SaveRuleResult.Invalid -> error = result.message
                                }
                            }
                        },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxWidth().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text("CARD", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(bank, { bank = it }, label = { Text("Bank name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(last4, { last4 = it }, label = { Text("Last 4") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Blank = match any card from this bank.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("SEND TO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
            BudgetDropdown(
                budgets = budgets,
                selected = selectedBudget,
                onSelect = { b -> budgetId = b.id; accountId = ""; viewModel.loadAccounts(b.id) },
            )
            AccountDropdown(
                accounts = accounts,
                selected = selectedAccount,
                enabled = budgetId.isNotBlank(),
                onSelect = { a -> accountId = a.id },
            )

            routePreview(bank, last4, selectedBudget?.name, selectedAccount?.name, currency.ifBlank { null })?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetDropdown(
    budgets: List<BudgetEntity>,
    selected: BudgetEntity?,
    onSelect: (BudgetEntity) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let { "${it.name} (${it.currencyCode})" } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Budget") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            budgets.forEach { b ->
                DropdownMenuItem(
                    text = { Text("${b.name} (${b.currencyCode})") },
                    onClick = { onSelect(b); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDropdown(
    accounts: List<AccountEntity>,
    selected: AccountEntity?,
    enabled: Boolean,
    onSelect: (AccountEntity) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Account") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { a ->
                DropdownMenuItem(text = { Text(a.name) }, onClick = { onSelect(a); expanded = false })
            }
        }
    }
}
