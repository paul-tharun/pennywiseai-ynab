package com.pennywiseai.ynab.ui.backfill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.ynab.capture.BackfillRun

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackfillScreen(viewModel: BackfillViewModel = hiltViewModel()) {
    val run by viewModel.run.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Import past transactions", style = MaterialTheme.typography.titleLarge)
        Text(
            "Only messages matching a route are imported.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        when (val r = run) {
            is BackfillRun.Running -> RunningState(r, onCancel = viewModel::cancel)
            is BackfillRun.Done -> {
                DoneState(r)
                Spacer(Modifier.height(12.dp))
                RangePicker(viewModel)
            }
            BackfillRun.Idle -> RangePicker(viewModel)
        }
    }
}

@Composable
private fun RunningState(run: BackfillRun.Running, onCancel: () -> Unit) {
    Text("Importing…", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    if (run.total > 0) {
        LinearProgressIndicator(
            progress = { run.done.toFloat() / run.total },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("${run.done} of ~${run.total}", style = MaterialTheme.typography.bodySmall)
    } else {
        // Fallback (design spec): no cheap total yet -> indeterminate bar + running tally.
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Scanning…", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "It keeps running if you leave this screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onCancel) { Text("Cancel import") }
}

@Composable
private fun DoneState(run: BackfillRun.Done) {
    Text(
        "Done · ${run.posted} posted · ${run.skipped} skipped · ${run.failed} failed",
        style = MaterialTheme.typography.titleMedium,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePicker(viewModel: BackfillViewModel) {
    var showCustom by remember { mutableStateOf(false) }

    Text("HOW FAR BACK", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7, 30, 90).forEach { days ->
            FilterChip(
                selected = false,
                onClick = { viewModel.startQuickRange(days) },
                label = { Text("$days days") },
            )
        }
        FilterChip(
            selected = showCustom,
            onClick = { showCustom = true },
            label = { Text("Custom…") },
        )
    }
    Text(
        "Already-imported transactions are skipped, so running this twice is safe.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    if (showCustom) {
        val state = rememberDateRangePickerState()
        AlertDialog(
            onDismissRequest = { showCustom = false },
            confirmButton = {
                val from = state.selectedStartDateMillis
                val to = state.selectedEndDateMillis
                TextButton(
                    enabled = from != null && to != null,
                    onClick = {
                        if (from != null && to != null) viewModel.startCustom(from, to)
                        showCustom = false
                    },
                ) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showCustom = false }) { Text("Cancel") } },
            text = { DateRangePicker(state = state) },
        )
    }
}
