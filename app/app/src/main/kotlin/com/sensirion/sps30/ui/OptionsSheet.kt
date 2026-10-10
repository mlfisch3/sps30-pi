package com.sensirion.sps30.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sensirion.sps30.data.AppConfig
import androidx.compose.ui.platform.LocalContext
import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.data.ReadoutStyle
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.net.TransportAvailability
import com.sensirion.sps30.wire.Wire
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheet(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    onSetY: () -> Unit,
    onToggleBackground: (Boolean) -> Unit,
    onExportCsv: () -> Unit,
    onSavePng: () -> Unit,
    onSelectMode: (ConnectionMode) -> Unit,
    onDismiss: () -> Unit,
) {
    // Disable swipe-down-to-dismiss (it clashes with the phone's notification-shade
    // pull-down from the top edge); the X button is the intended way out. The scrim
    // tap and Back still close via onDismissRequest.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    val config = state.config
    val measuring = state.daemonState == "measuring"
    val context = LocalContext.current

    // Sensor-button safety. Engaged by default; auto re-engages 20 s after it is
    // disengaged with no further interaction anywhere in the menu. `menuInteraction`
    // is bumped by any touch in the sheet (below), restarting the countdown.
    var safetyEngaged by remember { mutableStateOf(true) }
    var showShutdownConfirm by remember { mutableStateOf(false) }
    // Switching acquisition mode stops/starts and sleeps the physical sensor, so
    // it gets the same guard treatment as the Sensor buttons - its own toggle,
    // because the two sections sit far apart in a long sheet.
    var modeSafetyEngaged by remember { mutableStateOf(true) }
    var menuInteraction by remember { mutableIntStateOf(0) }
    LaunchedEffect(safetyEngaged, menuInteraction) {
        if (!safetyEngaged) {
            delay(20_000)
            safetyEngaged = true
        }
    }
    LaunchedEffect(modeSafetyEngaged, menuInteraction) {
        if (!modeSafetyEngaged) {
            delay(20_000)
            modeSafetyEngaged = true
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding() // keep the header/X clear of the status bar when tall
                .pointerInput(Unit) {
                    // Observe (don't consume) every touch-down to reset the safety timer.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        menuInteraction++
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Options", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) {
                    Text("✕", style = MaterialTheme.typography.titleLarge)
                }
            }

            Section("Connection")
            ConnectionSelector(
                current = config.connectionMode,
                // Re-checked on every touch in the sheet, so toggling Bluetooth in
                // the shade and coming back shows the button as live again.
                availability = remember(menuInteraction) {
                    ConnectionMode.entries.associateWith { TransportAvailability.of(context, it) }
                },
                connected = state.connection is ConnectionState.Connected,
                onSelect = onSelectMode,
            )
            Text(
                "Bluetooth (BLE) connects directly to the sensor host - use it when no " +
                    "Wi-Fi network is available. Disconnect stops this phone streaming; " +
                    "the sensor keeps recording and reconnecting fills in the gap.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )

            MeasurementHeader(
                engaged = modeSafetyEngaged,
                onToggle = { modeSafetyEngaged = !modeSafetyEngaged },
            )
            AcquisitionSection(
                profile = state.profile,
                power = state.power,
                supportsSleep = state.supportsSleep,
                daemonState = state.daemonState,
                safetyEngaged = modeSafetyEngaged,
                onSetMode = { mode ->
                    val p = state.profile
                    when (mode) {
                        MeasurementMode.OFF -> viewModel.pause()
                        MeasurementMode.CONTINUOUS, MeasurementMode.LOW_POWER -> {
                            // Leaving Off has to resume explicitly: set_profile is a
                            // no-op when the profile has not actually changed, so
                            // picking the mode you were already in before stopping
                            // would otherwise leave the sensor stopped.
                            if (state.daemonState == Wire.STATE_PAUSED) viewModel.resume()
                            viewModel.setProfile(
                                mode = if (mode == MeasurementMode.LOW_POWER) {
                                    Wire.MODE_DUTY_CYCLE
                                } else {
                                    Wire.MODE_CONTINUOUS
                                },
                                intervalSeconds = p?.intervalSeconds ?: 1.0,
                                cycleSeconds = p?.cycleSeconds ?: 900.0,
                                warmupSeconds = p?.warmupSeconds ?: 30.0,
                                averageSeconds = p?.averageSeconds ?: 30.0,
                            )
                        }
                    }
                },
                onSetInterval = { seconds ->
                    val p = state.profile
                    viewModel.setProfile(
                        mode = Wire.MODE_CONTINUOUS,
                        intervalSeconds = seconds,
                        cycleSeconds = p?.cycleSeconds ?: 900.0,
                        warmupSeconds = p?.warmupSeconds ?: 30.0,
                        averageSeconds = p?.averageSeconds ?: 30.0,
                    )
                },
                onSetCycle = { seconds ->
                    val p = state.profile
                    viewModel.setProfile(
                        mode = Wire.MODE_DUTY_CYCLE,
                        intervalSeconds = p?.intervalSeconds ?: 1.0,
                        cycleSeconds = seconds,
                        warmupSeconds = p?.warmupSeconds ?: 30.0,
                        averageSeconds = p?.averageSeconds ?: 30.0,
                    )
                },
            )

            SensorSection(
                measuring = measuring,
                viewModel = viewModel,
                engaged = safetyEngaged,
                onToggle = { safetyEngaged = !safetyEngaged },
                onShutdown = { showShutdownConfirm = true },
            )

            Section("Plot")
            SwitchRow("Y-axis autoscale", config = state.chart?.yAuto ?: true) { viewModel.setYAutoscale(it) }
            SwitchRow("Logarithmic Y axis", config = config.logY) { viewModel.setLogY(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onSetY, modifier = Modifier.weight(1f)) { Text("Set Y range") }
                OutlinedButton(onClick = viewModel::clearPlot, modifier = Modifier.weight(1f)) { Text("Clear plot") }
            }

            Section("Monitoring")
            SwitchRow("Background monitoring", config = config.backgroundMonitoring) { onToggleBackground(it) }

            // Two different things that both used to be called "export": writing
            // out what is on the plot, and fetching the sensor's whole archive
            // across the network. The labels now say which direction the data
            // moves and where it ends up, because mistaking the first for the
            // second means believing you have a backup that you do not.
            Section("Saving data")
            Text(
                "What is on the plot right now",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onExportCsv, modifier = Modifier.weight(1f)) {
                    Text("Save as CSV")
                }
                OutlinedButton(onClick = onSavePng, modifier = Modifier.weight(1f)) {
                    Text("Save as image")
                }
            }
            SessionCopyRow(state, viewModel)
            StoredSessionsRow(state, viewModel)

            Section("Watch")
            WatchRelayRow(config) { viewModel.updateConfig(config.copy(watchRelaySeconds = it)) }

            Section("Customize")
            PinCycleRow(config) { viewModel.updateConfig(config.withPinCycleCount(it)) }
            WindowPresetsEditor(config) { viewModel.updateConfig(config.copy(windowPresets = it)) }
            PlotIntervalsEditor(config) { viewModel.updateConfig(config.copy(plotIntervals = it)) }
            // Tucked in low: rarely changed. Hides the unreliable startup/clean spike.
            SwitchRow("Exclude sensor-spike readings", config = config.excludeFlagged) {
                viewModel.setExcludeFlagged(it)
            }

            Section("Who is connected")
            SharedAccessSection(
                state = state,
                onClaim = viewModel::claimControl,
                onRelease = viewModel::releaseControl,
            )

            Section("Alerts")
            AlertsSection(
                rules = config.alerts,
                activeIds = state.activeAlerts,
                onUpdate = viewModel::updateAlert,
                onTest = viewModel::testAlert,
            )

            // Near the bottom on purpose: a layout preference is set once and
            // then left alone, so it should not sit ahead of the controls people
            // actually come here to change.
            Section("Readouts")
            ReadoutStyleRow(config) { viewModel.setReadoutStyle(it) }

            Section("Configuration")
            ConfigSaveLoad(viewModel)

            Spacer(Modifier.width(1.dp))
        }
    }

    if (showShutdownConfirm) {
        AlertDialog(
            onDismissRequest = { showShutdownConfirm = false },
            title = { Text("Shut down the sensor?") },
            text = {
                Text(
                    "Recording stops and the sensor host powers off. Wait for its " +
                        "activity light to stop flickering before unplugging it - " +
                        "pulling power while it is still writing risks its storage.\n\n" +
                        "To start it again you will need to power-cycle it by hand."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.shutdownSensorHost()
                    showShutdownConfirm = false
                    onDismiss()
                }) { Text("Shut down") }
            },
            dismissButton = {
                TextButton(onClick = { showShutdownConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/** "Measurement" heading with its own safety toggle on the right. */
@Composable
private fun MeasurementHeader(engaged: Boolean, onToggle: () -> Unit) {
    HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "Measurement",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        SafetyToggle(engaged = engaged, onToggle = onToggle)
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    Text(
        title,
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun SwitchRow(label: String, config: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = config, onCheckedChange = onChange)
    }
}

/**
 * The Sensor controls, protected by a missile-switch-style safety. The three
 * buttons (Pause/Resume, Clean, Restart) disturb acquisition, so a translucent red
 * guard plate covers them until the user flips the safety toggle - placed top-right,
 * opposite the "Sensor" label. The safety re-engages automatically after 20 s
 * without any interaction in this section.
 */
@Composable
private fun SensorSection(
    measuring: Boolean,
    viewModel: DashboardViewModel,
    engaged: Boolean,
    onToggle: () -> Unit,
    onShutdown: () -> Unit,
) {
    HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "Sensor",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        SafetyToggle(engaged = engaged, onToggle = onToggle)
    }
    Text(
        "These act on the physical sensor and disturb its readings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // One guard over both rows: two stacked "SAFETY ON" overlays read as a bug.
    Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (measuring) {
                    Button(onClick = viewModel::pause, modifier = Modifier.weight(1f)) { Text("Pause sensor") }
                } else {
                    Button(onClick = viewModel::resume, modifier = Modifier.weight(1f)) { Text("Resume sensor") }
                }
                OutlinedButton(onClick = viewModel::clean, modifier = Modifier.weight(1f)) { Text("Clean sensor") }
                OutlinedButton(onClick = viewModel::restart, modifier = Modifier.weight(1f)) { Text("Restart") }
            }
            OutlinedButton(onClick = onShutdown, modifier = Modifier.fillMaxWidth()) {
                Text("Shut down sensor")
            }
        }
        SafetyGuard(engaged = engaged)
    }
}

/** A compact sliding safety switch: red "SAFE" (guard down) <-> green "ARMED" (up). */
@Composable
internal fun SafetyToggle(engaged: Boolean, onToggle: () -> Unit) {
    val red = Color(0xFFE53935)
    val green = Color(0xFF43A047)
    val color = if (engaged) red else green
    val knobBias by animateFloatAsState(
        targetValue = if (engaged) -1f else 1f,
        animationSpec = tween(220),
        label = "safety-knob",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (engaged) "SAFE" else "ARMED",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 22.dp)
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.20f))
                .border(1.5.dp, color, RoundedCornerShape(50)),
        ) {
            Box(
                modifier = Modifier
                    .align(BiasAlignment(horizontalBias = knobBias, verticalBias = 0f))
                    .padding(horizontal = 3.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
    }
}

/** The translucent red guard plate. Covers and blocks the Sensor buttons when the
 *  safety is engaged; flips up on its top hinge (rotationX) and vanishes when off. */
@Composable
internal fun BoxScope.SafetyGuard(engaged: Boolean) {
    val rot by animateFloatAsState(
        targetValue = if (engaged) 0f else -108f,
        animationSpec = tween(durationMillis = 420),
        label = "safety-guard",
    )
    if (rot <= -104f) return // fully lifted: gone, buttons fully exposed and tappable
    val red = Color(0xFFE53935)
    val guardInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer {
                rotationX = rot
                transformOrigin = TransformOrigin(0.5f, 0f) // hinge along the top edge
                cameraDistance = 18f * density
                alpha = (1f + rot / 108f).coerceIn(0f, 1f)
            }
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    listOf(red.copy(alpha = 0.42f), red.copy(alpha = 0.20f), red.copy(alpha = 0.40f)),
                ),
            )
            .border(1.5.dp, red.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
            .then(
                // Only intercept taps while engaged (protecting the buttons); when
                // lifting, it lets them through. The empty onClick just consumes the tap.
                if (engaged) {
                    Modifier.clickable(
                        interactionSource = guardInteraction,
                        indication = null,
                        onClick = {},
                    )
                } else {
                    Modifier
                },
            ),
        // Top-aligned rather than centred: the guard now covers whole sections,
        // not just a single button row, and a centred label lands in the middle of
        // the controls it is covering - which reads as text colliding with chips
        // rather than as a plate laid over them.
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            "🔒  SAFETY ON",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun PinCycleRow(config: AppConfig, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Pin cycle length (n)")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { onChange(config.pinCycleCount - 1) }, enabled = config.pinCycleCount > 1) { Text("–") }
            Text("${config.pinCycleCount}", modifier = Modifier.width(28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            OutlinedButton(onClick = { onChange(config.pinCycleCount + 1) }) { Text("+") }
        }
    }
}

@Composable
private fun VisiblePmRow(config: AppConfig, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Series shown on plot")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (field in Wire.PM_FIELDS) {
                FilterChip(
                    selected = field in config.visiblePm,
                    onClick = { onToggle(field) },
                    label = { Text(SeriesColors.label[field] ?: field) },
                )
            }
        }
    }
}

@Composable
private fun WindowPresetsEditor(config: AppConfig, onApply: (List<com.sensirion.sps30.data.WindowPreset>) -> Unit) {
    var text by remember(config.windowPresets) { mutableStateOf(AppConfig.presetsToText(config.windowPresets)) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Window buttons")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                placeholder = { Text("10s, 1m, 1h, all") },
            )
            Button(onClick = { onApply(AppConfig.parsePresets(text)) }) { Text("Apply") }
        }
    }
}

/** The averaging buttons on the main view, edited as a comma-separated list. */
@Composable
private fun PlotIntervalsEditor(config: AppConfig, onApply: (List<Double>) -> Unit) {
    var text by remember(config.plotIntervals) {
        mutableStateOf(AppConfig.intervalsToText(config.plotIntervals))
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Averaging buttons")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                placeholder = { Text("1s, 5s, 30s, 1m, 1h") },
            )
            Button(onClick = { onApply(AppConfig.parseIntervals(text)) }) { Text("Apply") }
        }
        Text(
            "How much time each plotted point averages over. 1s means every reading " +
                "is drawn. Changes what you see, never what is recorded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Layout of the two PM readout buttons on the main view. */
@Composable
private fun ReadoutStyleRow(config: AppConfig, onPick: (ReadoutStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Readout layout")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = config.readoutStyle == ReadoutStyle.STACKED,
                onClick = { onPick(ReadoutStyle.STACKED) },
                label = { Text("Stacked") },
            )
            FilterChip(
                selected = config.readoutStyle == ReadoutStyle.INLINE,
                onClick = { onPick(ReadoutStyle.INLINE) },
                label = { Text("One line") },
            )
        }
        Text(
            "Stacked puts the name, reading and unit on three lines, which gives " +
                "the number the most presence. One line fills the width instead, " +
                "and is easier on space at the cost of a busier readout.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfigSaveLoad(viewModel: DashboardViewModel) {
    var refresh by remember { mutableIntStateOf(0) }
    val names = remember(refresh) { viewModel.savedConfigNames() }
    var saveName by remember { mutableStateOf("") }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = saveName,
            onValueChange = { saveName = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
            placeholder = { Text("name") },
        )
        Button(
            onClick = { viewModel.saveConfigAs(saveName); saveName = ""; refresh++ },
            enabled = saveName.isNotBlank(),
        ) { Text("Save") }
    }
    if (names.isNotEmpty()) {
        Text("Load saved:", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in names) {
                FilterChip(
                    selected = false,
                    onClick = { viewModel.loadSavedConfig(name) },
                    label = { Text(name) },
                )
            }
        }
    }
    TextButton(onClick = { viewModel.resetConfigToDefault() }) { Text("Reset to defaults") }
}

/**
 * How often the phone pushes a reading to the watch. Every push wakes the watch's
 * radio and forces a complication redraw, so this is the main lever on watch
 * battery life - hence the explicit note rather than a bare row of chips.
 */
@Composable
private fun WatchRelayRow(config: AppConfig, onPick: (Int) -> Unit) {
    Text("Send a reading to the watch at most every", style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (seconds in AppConfig.WATCH_RELAY_CHOICES) {
            FilterChip(
                selected = config.watchRelaySeconds == seconds,
                onClick = { onPick(seconds) },
                label = { Text(if (seconds < 60) "${seconds}s" else "${seconds / 60}m") },
            )
        }
    }
    Text(
        "This is the fastest the watch will ever be updated - longer means less " +
            "watch battery used. Within that limit a reading is only sent when it " +
            "has actually changed, so in steady air the watch is updated less often " +
            "than this, not more.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
