package com.labsage.app.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labsage.app.R
import com.labsage.app.data.sync.HistorySyncState

@Composable
fun SyncStatusBanner(state: HistorySyncState, onRetry: () -> Unit) {
    if (state.isSyncing) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    val error = state.errorMessage ?: return
    val colors = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxWidth(), color = colors.errorContainer) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.CloudSync, contentDescription = null, tint = colors.onErrorContainer)
            Text(
                text = stringResource(R.string.history_sync_failed, error),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onErrorContainer
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.history_sync_retry))
            }
        }
    }
}
