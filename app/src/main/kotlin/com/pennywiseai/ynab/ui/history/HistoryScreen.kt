package com.pennywiseai.ynab.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.ui.common.formatAmount
import com.pennywiseai.ynab.ui.common.statusLabel

@Composable
fun HistoryScreen(
    onMapRoute: (String, String?) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            FilterChip(selected = filter == null, onClick = { viewModel.setFilter(null) }, label = { Text("All") })
            MessageStatus.entries.forEach { s ->
                FilterChip(
                    selected = filter == s,
                    onClick = { viewModel.setFilter(s) },
                    label = { Text(statusLabel(s)) },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(items, key = { it.importId }) { item ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${item.bankName} · ${formatAmount(item.amount, item.currency)}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(statusLabel(item.status), style = MaterialTheme.typography.bodyMedium)
                        item.error?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row {
                            if (item.status == MessageStatus.FAILED) {
                                TextButton(onClick = { viewModel.retry(item) }) { Text("Retry") }
                            }
                            if (item.status == MessageStatus.SKIPPED_UNROUTED) {
                                TextButton(onClick = { onMapRoute(item.bankName, item.last4) }) { Text("Map this route") }
                            }
                        }
                    }
                }
            }
        }
    }
}
