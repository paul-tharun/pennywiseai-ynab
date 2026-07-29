package com.pennywiseai.ynab.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.data.local.entity.ProcessedMessageEntity
import com.pennywiseai.ynab.ui.common.StatusPill
import com.pennywiseai.ynab.ui.common.absoluteTime
import com.pennywiseai.ynab.ui.common.formatAmount
import com.pennywiseai.ynab.ui.common.relativeTime
import com.pennywiseai.ynab.ui.common.statusLabel
import com.pennywiseai.ynab.ui.theme.LocalStatusColors

@Composable
fun HomeScreen(
    onMapRoute: (String, String?) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val rescan by viewModel.rescanState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        HomeHeader(
            lastActivityMillis = stats.lastActivityMillis,
            rescan = rescan,
            onRescan = viewModel::rescan,
        )
        StatStrip(
            stats = stats,
            filter = filter,
            onSelect = viewModel::setFilter,
        )
        HorizontalDivider()
        ListHeader(filter = filter, count = items.size, onClear = { viewModel.setFilter(null) })

        if (items.isEmpty()) {
            EmptyState(filtered = filter != null)
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(items, key = { it.importId }) { item ->
                    TransactionRow(
                        item = item,
                        onRetry = { viewModel.retry(item) },
                        onMapRoute = { onMapRoute(item.bankName, item.last4) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    lastActivityMillis: Long?,
    rescan: RescanState,
    onRescan: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("₹", style = MaterialTheme.typography.titleLarge) }

        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            val primary = when {
                rescan is RescanState.Result && rescan.imported > 0 -> "Imported ${rescan.imported}"
                rescan is RescanState.Result -> "Checked · nothing new"
                lastActivityMillis == null -> "No activity yet"
                else -> "Last transaction · ${relativeTime(System.currentTimeMillis(), lastActivityMillis)}"
            }
            Text(primary, style = MaterialTheme.typography.titleMedium)
            if (lastActivityMillis != null) {
                Text(
                    "Today at ${absoluteTime(lastActivityMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (rescan is RescanState.Running) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRescan) {
                Icon(Icons.Filled.Refresh, contentDescription = "Re-scan last 24 hours")
            }
        }
    }
}

@Composable
private fun StatStrip(
    stats: HomeStats,
    filter: MessageStatus?,
    onSelect: (MessageStatus?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile("Posted", stats.posted, MessageStatus.POSTED, filter, onSelect, Modifier.weight(1f))
        StatTile("Failed", stats.failed, MessageStatus.FAILED, filter, onSelect, Modifier.weight(1f))
        StatTile("Unrouted", stats.unrouted, MessageStatus.SKIPPED_UNROUTED, filter, onSelect, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(
    label: String,
    count: Int,
    status: MessageStatus,
    filter: MessageStatus?,
    onSelect: (MessageStatus?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = filter == status
    val container: Color = when (status) {
        MessageStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        MessageStatus.SKIPPED_UNROUTED -> LocalStatusColors.current.warningContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .then(
                if (active) Modifier.border(
                    2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(12.dp),
                ) else Modifier,
            )
            .clickable { onSelect(if (active) null else status) }
            .padding(vertical = 12.dp, horizontal = 12.dp),
    ) {
        Text("$count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ListHeader(filter: MessageStatus?, count: Int, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (filter == null) "Recent" else "${statusLabel(filter)} · $count",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (filter != null) TextButton(onClick = onClear) { Text("Show all ✕") }
    }
}

@Composable
private fun TransactionRow(
    item: ProcessedMessageEntity,
    onRetry: () -> Unit,
    onMapRoute: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${item.bankName} ·${item.last4 ?: "----"}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    formatAmount(item.amount, item.currency),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(item.status)
        }
        item.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row {
            if (item.status == MessageStatus.FAILED) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            if (item.status == MessageStatus.SKIPPED_UNROUTED) {
                TextButton(onClick = onMapRoute) { Text("Map this card →") }
            }
        }
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (filtered) "Nothing here" else "No transactions yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.padding(4.dp))
        Text(
            if (filtered) "Try a different filter." else "Bank texts will appear here as they arrive.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
