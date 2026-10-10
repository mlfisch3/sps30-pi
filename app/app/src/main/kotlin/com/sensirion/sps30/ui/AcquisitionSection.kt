package com.sensirion.sps30.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import kotlin.math.roundToInt

/** What the sensor is doing, as one three-way choice. */
enum class MeasurementMode { CONTINUOUS, LOW_POWER, OFF }

/**
 * Acquisition mode: continuous, Sensirion's low-power duty cycle, or off.
 *
 * Off means measurement stopped with the host still running and reachable - it is
 * deliberately not the same thing as shutting the host down, which is a separate
 * button because it needs a physical power-cycle to undo.
 *
 * The distinction the UI has to make honestly is that a longer *continuous*
 * interval does not save power - the fan and laser never stop - even though it
 * does reduce how often the host polls the sensor and how fast the CSV grows.
 * Only duty cycling actually sleeps the sensor.
 */
@Composable
fun AcquisitionSection(
    profile: ServerMessage.AcquisitionProfile?,
    power: ServerMessage.PowerStatus?,
    supportsSleep: Boolean,
    daemonState: String?,
    safetyEngaged: Boolean,
    onSetMode: (MeasurementMode) -> Unit,
    onSetInterval: (Double) -> Unit,
    onSetCycle: (Double) -> Unit,
) {
    // A host that is powering off is not measuring either. Without this the chips
    // fall through to their default and show "Continuous" for a sensor whose fan
    // the user just watched stop.
    val off = daemonState == Wire.STATE_PAUSED || daemonState == Wire.STATE_SHUTDOWN
    val dutyCycling = profile?.dutyCycling == true
    val mode = when {
        off -> MeasurementMode.OFF
        dutyCycling -> MeasurementMode.LOW_POWER
        else -> MeasurementMode.CONTINUOUS
    }
    val cycle = profile?.cycleSeconds ?: 900.0
    val interval = profile?.intervalSeconds ?: 1.0

    // Everything in this section changes what the physical sensor is doing - the
    // mode obviously, but the interval too, since it sets how often the host
    // polls the sensor. So the whole thing sits under one guard.
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ModeChip("Continuous", mode == MeasurementMode.CONTINUOUS, true) {
                    onSetMode(MeasurementMode.CONTINUOUS)
                }
                ModeChip("Low power", mode == MeasurementMode.LOW_POWER, supportsSleep) {
                    onSetMode(MeasurementMode.LOW_POWER)
                }
                ModeChip("Off", mode == MeasurementMode.OFF, true) {
                    onSetMode(MeasurementMode.OFF)
                }
            }

            if (!supportsSleep) {
                Note("Low power needs sensor firmware 2.0 or newer; this one is older.")
            }

            when (mode) {
                MeasurementMode.OFF -> Note(
                    "Measurement is stopped. The sensor host is still running and " +
                        "connected, so this resumes instantly - unlike Shut down, " +
                        "which powers the host off until you unplug and replug it."
                )

                MeasurementMode.LOW_POWER -> {
                    Text("Measure once every", style = MaterialTheme.typography.bodyMedium)
                    ChipRow(AppConfig.DUTY_CYCLE_PERIODS, cycle, onSetCycle)
                    val warmup = profile?.warmupSeconds ?: 30.0
                    val average = profile?.averageSeconds ?: 30.0
                    Note(
                        "Each cycle wakes the sensor, discards ${warmup.roundToInt()}s while " +
                            "the fan spins up, averages ${average.roundToInt()}s of readings " +
                            "into one point, then sleeps. This is Sensirion's recommended " +
                            "low-power sequence."
                    )
                    if (cycle >= 43_200.0) {
                        Note(
                            "At this interval the sensor is asleep over 99.9% of the time, " +
                                "so the battery drains mostly by itself rather than by " +
                                "measuring. Intended for storage or long absences."
                        )
                    }
                }

                MeasurementMode.CONTINUOUS -> {
                    Text("Read the sensor every", style = MaterialTheme.typography.bodyMedium)
                    ChipRow(AppConfig.CONTINUOUS_INTERVALS, interval, onSetInterval)
                    Note(
                        if (interval > AppConfig.SUGGEST_DUTY_CYCLE_ABOVE) {
                            "At this interval, low power mode records just as often and uses " +
                                "far less energy - the sensor sleeps between readings instead " +
                                "of running continuously."
                        } else {
                            "This sets how often the host asks the sensor for a reading, so it " +
                                "controls how fast the stored data grows. It saves little power: " +
                                "the fan and laser stay on between readings either way."
                        }
                    )
                }
            }

            power?.averageMa?.let { ma ->
                val draw = if (mode == MeasurementMode.OFF) "0 mA (measurement stopped)"
                    else "${"%.1f".format(ma)} mA average"
                Note("Estimated sensor draw: $draw.")
            }
        }
        SafetyGuard(engaged = safetyEngaged)
    }
}

/**
 * A mode button whose selected state is unmistakable.
 *
 * A plain FilterChip only shows selection as a subtle tint plus a check, which
 * was not readable at a glance - the mode had to be inferred from the
 * explanatory text below. Selected chips get the full primary colour and a bold
 * label so the active mode is obvious without reading anything.
 */
@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = {
            Text(
                label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        },
        leadingIcon = if (selected) {
            { Text("●", style = MaterialTheme.typography.labelMedium) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun ChipRow(options: List<Double>, current: Double, onPick: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (seconds in options) {
            FilterChip(
                selected = current == seconds,
                onClick = { onPick(seconds) },
                label = { Text(labelFor(seconds)) },
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "30s", "15m", "1h", "24h" - the shortest honest rendering of a period. */
private fun labelFor(seconds: Double): String = when {
    seconds < 60 -> "${seconds.roundToInt()}s"
    seconds < 3600 -> "${(seconds / 60).roundToInt()}m"
    else -> "${(seconds / 3600).roundToInt()}h"
}
