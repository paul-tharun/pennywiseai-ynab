package com.pennywiseai.ynab.ui.backfill

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackfillScreen(viewModel: BackfillViewModel = hiltViewModel()) {
    val running by viewModel.running.collectAsStateWithLifecycle()
    val state = rememberDateRangePickerState()

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Import past transactions")
        DateRangePicker(state = state, modifier = Modifier.fillMaxWidth().weight(1f, fill = false))
        val from = state.selectedStartDateMillis
        val to = state.selectedEndDateMillis
        if (running) {
            Text("Importing… a progress notification shows the count.")
            OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancel import") }
        } else {
            Button(
                enabled = from != null && to != null,
                onClick = { if (from != null && to != null) viewModel.start(from, to) },
            ) { Text("Start import") }
        }
    }
}
