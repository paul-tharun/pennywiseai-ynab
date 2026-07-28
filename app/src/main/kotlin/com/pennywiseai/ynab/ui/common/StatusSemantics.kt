package com.pennywiseai.ynab.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pennywiseai.ynab.data.local.MessageStatus
import com.pennywiseai.ynab.ui.theme.LocalStatusColors

/** The four semantic buckets the five real statuses collapse into. */
enum class SemanticColor { SUCCESS, WARNING, ERROR, NEUTRAL }

/** Pure status -> semantic bucket (see Global Constraints). Unit-tested in StatusSemanticsTest. */
fun semanticColor(status: MessageStatus): SemanticColor = when (status) {
    MessageStatus.POSTED -> SemanticColor.SUCCESS
    MessageStatus.SKIPPED_UNROUTED -> SemanticColor.WARNING
    MessageStatus.SKIPPED_CURRENCY_MISMATCH -> SemanticColor.WARNING
    MessageStatus.FAILED -> SemanticColor.ERROR
    MessageStatus.SKIPPED_NON_TRANSACTION -> SemanticColor.NEUTRAL
}

@Composable
fun statusContainerColor(status: MessageStatus): Color = when (semanticColor(status)) {
    SemanticColor.SUCCESS -> LocalStatusColors.current.successContainer
    SemanticColor.WARNING -> LocalStatusColors.current.warningContainer
    SemanticColor.ERROR -> MaterialTheme.colorScheme.errorContainer
    SemanticColor.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
fun statusContentColor(status: MessageStatus): Color = when (semanticColor(status)) {
    SemanticColor.SUCCESS -> LocalStatusColors.current.onSuccessContainer
    SemanticColor.WARNING -> LocalStatusColors.current.onWarningContainer
    SemanticColor.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    SemanticColor.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Compact status chip: filled container + label, colored by [semanticColor]. */
@Composable
fun StatusPill(status: MessageStatus, modifier: Modifier = Modifier) {
    Text(
        text = statusLabel(status),
        style = MaterialTheme.typography.labelSmall,
        color = statusContentColor(status),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(statusContainerColor(status))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
