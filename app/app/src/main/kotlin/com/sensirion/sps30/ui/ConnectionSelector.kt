package com.sensirion.sps30.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.net.TransportAvailability

/**
 * Wi-Fi / Bluetooth / Disconnect, where the greyed-out button *is* the state
 * indicator: whichever mode you are in cannot be tapped, because tapping it would
 * do nothing.
 *
 * Grey alone is ambiguous though - a radio that is switched off is also unusable -
 * so the current mode additionally carries an accent ring and a "Connected" caption,
 * while a genuinely unavailable one is plain grey with the reason underneath. The
 * distinguishing cue is shape plus text rather than colour, so it survives both
 * themes and colour-blindness.
 */
@Composable
fun ConnectionSelector(
    current: ConnectionMode,
    availability: Map<ConnectionMode, TransportAvailability>,
    connected: Boolean,
    onSelect: (ConnectionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in ConnectionMode.entries) {
            val available = availability[mode] ?: TransportAvailability.USABLE
            val isCurrent = mode == current
            ConnectionButton(
                label = label(mode),
                isCurrent = isCurrent,
                enabled = !isCurrent && available.usable,
                caption = when {
                    isCurrent -> currentCaption(mode, connected)
                    !available.usable -> available.reason
                    else -> null
                },
                onClick = { onSelect(mode) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ConnectionButton(
    label: String,
    isCurrent: Boolean,
    enabled: Boolean,
    caption: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            // The ring is what separates "you are here" from "unavailable"; both
            // render with the disabled content colour.
            border = if (isCurrent) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            },
        ) {
            Text(label, maxLines = 1)
        }
        // Reserve the caption row so the buttons never shift as state changes.
        Text(
            text = caption.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().heightIn(min = 16.dp),
        )
    }
}

private fun label(mode: ConnectionMode) = when (mode) {
    ConnectionMode.WIFI -> "Wi-Fi"
    ConnectionMode.BLUETOOTH -> "Bluetooth"
    ConnectionMode.NONE -> "Disconnect"
}

/** The current mode's caption doubles as link status, so a mode we are *trying*
 *  to use does not falsely claim to be connected. */
private fun currentCaption(mode: ConnectionMode, connected: Boolean) = when {
    mode == ConnectionMode.NONE -> "Not connected"
    connected -> "Connected"
    else -> "Connecting…"
}
