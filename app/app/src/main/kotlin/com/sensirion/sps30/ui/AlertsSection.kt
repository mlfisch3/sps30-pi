package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.AlertRule

/**
 * Threshold alerts, one editable row per level.
 *
 * Levels are independent rather than a fixed escalation ladder: any combination
 * of reactions may be enabled on any level, and none need be enabled at all, so
 * "notify me at 20 but only buzz me at 55" and "stay completely silent" are both
 * expressible. Each row carries its own test button, because waiting for
 * genuinely bad air to find out whether alerts work is not a usable test loop.
 */
@Composable
fun AlertsSection(
    rules: List<AlertRule>,
    activeIds: Set<String>,
    onUpdate: (AlertRule) -> Unit,
    onTest: (AlertRule) -> Unit,
) {
    Text(
        "Alerts fire when PM2.5 crosses a level, then re-arm once it drops clear. " +
            "They live on this phone only - other people connected to the same " +
            "sensor keep their own.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (rule in rules) {
        AlertRow(
            rule = rule,
            active = rule.id in activeIds,
            onUpdate = onUpdate,
            onTest = onTest,
        )
    }
}

@Composable
private fun AlertRow(
    rule: AlertRule,
    active: Boolean,
    onUpdate: (AlertRule) -> Unit,
    onTest: (AlertRule) -> Unit,
) {
    var threshold by remember(rule.threshold) { mutableStateOf(rule.threshold.toInt().toString()) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                rule.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (active) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
            if (active) {
                // Say so plainly rather than relying on the colour alone.
                Text(
                    "breached now",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Switch(
                checked = rule.enabled,
                onCheckedChange = { onUpdate(rule.copy(enabled = it)) },
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = threshold,
                onValueChange = { text ->
                    threshold = text.filter { it.isDigit() }
                    threshold.toDoubleOrNull()?.let { onUpdate(rule.copy(threshold = it)) }
                },
                singleLine = true,
                enabled = rule.enabled,
                modifier = Modifier.width(120.dp),
                label = { Text("ug/m3") },
            )
            TextButton(
                onClick = { onTest(rule) },
                enabled = rule.hasReaction,
            ) { Text("Test") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReactionChip("Notify", rule.notify, rule.enabled) {
                onUpdate(rule.copy(notify = it))
            }
            ReactionChip("Vibrate", rule.vibrate, rule.enabled) {
                onUpdate(rule.copy(vibrate = it))
            }
            ReactionChip("Sound", rule.sound, rule.enabled) {
                onUpdate(rule.copy(sound = it))
            }
            ReactionChip("On plot", rule.highlightPlot, rule.enabled) {
                onUpdate(rule.copy(highlightPlot = it))
            }
        }
    }
}

@Composable
private fun ReactionChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = { onToggle(!selected) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    )
}
