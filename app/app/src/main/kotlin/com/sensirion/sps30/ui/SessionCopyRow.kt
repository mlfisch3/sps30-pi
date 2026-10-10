package com.sensirion.sps30.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.SessionBackup

/**
 * Copying every recorded session off the sensor and onto this device.
 *
 * Kept visually separate from saving the plot, and worded to say plainly which
 * way the data travels: the sensor keeps recording whether or not anyone is
 * watching, so its archive is much larger than whatever happens to be plotted,
 * and the two actions are not substitutes for one another.
 */
@Composable
fun SessionCopyRow(state: DashboardUiState, viewModel: DashboardViewModel) {
    var explain by remember { mutableStateOf(false) }
    val backup = state.backup

    Text(
        "Everything the sensor has recorded",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = viewModel::copySessionsToThisDevice,
            enabled = backup !is SessionBackup.Receiving && backup !is SessionBackup.Requested,
            modifier = Modifier.weight(1f),
        ) { Text("Copy all sessions to this device") }
        Text(
            if (explain) "▲" else "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { explain = !explain }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }

    AnimatedVisibility(visible = explain) {
        Text(
            "The sensor records continuously and keeps every session, including " +
                "the ones from before this app was installed or while it was " +
                "closed. This copies all of them here as a single zip of CSV " +
                "files, one per session, and leaves the originals on the sensor. " +
                "It is compressed on the way over, which keeps the radio on for " +
                "much less time than sending it raw would - the transfer, not the " +
                "compression, is what costs the sensor its battery.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    when (backup) {
        is SessionBackup.Requested -> Text(
            "Asking the sensor what it has…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is SessionBackup.Receiving -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(
                progress = { backup.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${mb(backup.received)} of ${mb(backup.total)} · " +
                    "${backup.sessions} sessions · ${"%,d".format(backup.rows)} readings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is SessionBackup.Saved -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Saved to ${backup.location}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // The location stays on screen until dismissed rather than flashing
            // past in a toast: it is the one piece of information the user needs
            // afterwards, and a copy this large is not something to repeat
            // because the message vanished before it was read.
            Text(
                "${backup.sessions} sessions · ${"%,d".format(backup.rows)} readings · " +
                    "${mb(backup.bytes)} zip",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = viewModel::dismissBackupResult) { Text("Done") }
        }

        is SessionBackup.Failed -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Copy failed: ${backup.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = viewModel::dismissBackupResult) { Text("Dismiss") }
        }

        SessionBackup.Idle -> Unit
    }
}

private fun mb(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1e3)
    else -> "$bytes B"
}
