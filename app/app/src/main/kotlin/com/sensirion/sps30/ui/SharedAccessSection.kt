package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Who else is watching this sensor, and who may currently change it.
 *
 * The sensor serves everyone on the network at once, but only one person at a
 * time may pause it, clean it or change its mode - otherwise one tap could stop
 * everybody else's recording without them ever knowing why. Watching is always
 * free; control is a lease that expires on its own, so a phone that goes out of
 * range cannot leave the sensor stuck.
 */
@Composable
fun SharedAccessSection(
    state: DashboardUiState,
    onClaim: () -> Unit,
    onRelease: () -> Unit,
) {
    val others = state.watchers.filter { it.id != state.clientId }
    val holderIsMe = state.controlHolder != null && state.controlHolder == state.clientId

    Text(
        when {
            state.watchers.isEmpty() -> "Connected."
            others.isEmpty() -> "You are the only one connected."
            others.size == 1 -> "You and ${others.single().name} are connected."
            else -> "You and ${others.size} others are connected: " +
                others.joinToString(", ") { it.name }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            when {
                holderIsMe -> "You have control"
                state.controlHolder == null -> "Nobody has control"
                else -> "${state.controlHolderName ?: "Someone else"} has control"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (state.hasControl) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        if (holderIsMe) {
            OutlinedButton(onClick = onRelease) { Text("Release") }
        } else if (state.controlHolder != null) {
            OutlinedButton(onClick = onClaim) { Text("Take control") }
        }
    }

    Text(
        if (state.controlHolder == null) {
            "Control is taken automatically the first time you change something."
        } else {
            "Control returns automatically if it goes unused for two minutes."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
