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
import com.sensirion.sps30.data.StoredImport
import com.sensirion.sps30.data.TimeSeriesHistory
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Reading the sessions already stored on this device back into the plot.
 *
 * The counterpart to copying them off the sensor, and deliberately right beneath
 * it: until now the copy produced a zip that this app could write but not read,
 * which made the whole record something the user owned and could not look at.
 */
@Composable
fun StoredSessionsRow(state: DashboardUiState, viewModel: DashboardViewModel) {
    var explain by remember { mutableStateOf(false) }
    val import = state.storedImport
    val busy = import is StoredImport.Scanning || import is StoredImport.Loading

    Text(
        "Sessions already stored on this device",
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
            onClick = viewModel::loadStoredSessions,
            enabled = !busy && import !is StoredImport.Loaded,
            modifier = Modifier.weight(1f),
        ) { Text(if (import is StoredImport.Loaded) "Loaded into the plot" else "Add them to the plot") }
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
            "Reads back every session copied here from the sensor and plots them " +
                "beside the live trace. There is far more of it than a phone can " +
                "hold at full resolution, so it is averaged into short windows on " +
                "the way in - enough to see where the recordings are, what the air " +
                "was doing, and which days have data at all. Recordings made at " +
                "different times are never joined by a line, so a break in the " +
                "trace is a real gap and not a straight run between two dates. " +
                "Use \"all\" to see the whole span; Auto Set stays with the live " +
                "session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    when (import) {
        StoredImport.Scanning -> Text(
            "Looking for stored sessions…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is StoredImport.Loading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(
                progress = { import.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${import.sessionsDone} of ${import.sessionsTotal} sessions · " +
                    "${"%,d".format(import.points)} points",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is StoredImport.Loaded -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${import.sessions} sessions · ${"%,d".format(import.points)} points",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${span(import.start, import.end)} · from ${"%,d".format(import.rows)} " +
                    "readings averaged over ${window(import.bucketSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Say what was left out. A summary that counts only what worked
            // reads as "all of it", and these files genuinely do include empty
            // ones and older layouts.
            val omitted = buildList {
                if (import.empty > 0) add("${import.empty} held no readings")
                if (import.unrecognised > 0) add("${import.unrecognised} could not be read")
                if (import.truncated) add("stopped at the point limit - the oldest are missing")
            }
            if (omitted.isNotEmpty()) {
                Text(
                    omitted.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = viewModel::clearStoredSessions) { Text("Remove from the plot") }
        }

        is StoredImport.Failed -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                import.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = viewModel::clearStoredSessions) { Text("Dismiss") }
        }

        StoredImport.Idle -> Unit
    }
}

private val DAY = DateTimeFormatter.ofPattern("MMM d")

private fun span(start: Double, end: Double): String {
    if (start <= 0.0 || end <= 0.0) return "no readings"
    fun day(e: Double) = Instant.ofEpochSecond(e.toLong())
        .atZone(TimeSeriesHistory.EASTERN).format(DAY)
    val from = day(start)
    val to = day(end)
    return if (from == to) from else "$from to $to"
}

private fun window(seconds: Double): String = when {
    seconds >= 3600 -> "%.0f h".format(seconds / 3600)
    seconds >= 60 -> "%.0f min".format(seconds / 60)
    else -> "%.0f s".format(seconds)
}
