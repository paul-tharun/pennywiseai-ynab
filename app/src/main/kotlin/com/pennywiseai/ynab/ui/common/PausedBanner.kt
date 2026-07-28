package com.pennywiseai.ynab.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.pennywiseai.ynab.ui.settings.SettingsViewModel

/**
 * Global banner shown across every screen while postingPaused is set (401 / no token).
 * Backed by SettingsViewModel.paused (PostingStateStore.observePaused). Prominent per the
 * design spec's "surface it prominently (banner + notification)".
 */
@Composable
fun PausedBanner(viewModel: SettingsViewModel = hiltViewModel()) {
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    if (paused) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Posting paused — your YNAB token is missing or invalid. Update it in Settings.",
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
