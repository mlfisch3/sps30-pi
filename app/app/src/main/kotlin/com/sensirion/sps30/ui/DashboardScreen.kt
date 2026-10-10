package com.sensirion.sps30.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.net.WifiNetworkBinder
import com.sensirion.sps30.service.Sps30Service
import com.sensirion.sps30.wire.Wire
import kotlin.math.roundToInt


@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = viewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var showYDialog by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Theme colours captured here so the off-screen PNG render matches the UI.
    val pngBg = MaterialTheme.colorScheme.surface
    val pngAxis = MaterialTheme.colorScheme.outline
    val pngGrid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val pngLabel = MaterialTheme.colorScheme.onSurfaceVariant

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Start regardless of the grant: the service still runs; the notification
        // simply won't be visible until the user allows notifications.
        Sps30Service.start(context)
    }
    val toggleBackground: (Boolean) -> Unit = { enable ->
        if (enable) {
            val needsPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsPerm) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else Sps30Service.start(context)
        } else {
            Sps30Service.stop(context)
        }
    }
    // Bluetooth mode needs runtime BLE permissions (SCAN/CONNECT on 12+, else location).
    val blePerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val blePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // Switch either way: if denied, BleTransport simply can't scan and the banner
        // shows the failure, prompting the user to grant it in Settings.
        viewModel.setConnectionMode(ConnectionMode.BLUETOOTH)
    }
    val onSelectMode: (ConnectionMode) -> Unit = { mode ->
        if (mode == ConnectionMode.BLUETOOTH) {
            val need = blePerms.any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (need) blePermLauncher.launch(blePerms)
            else viewModel.setConnectionMode(ConnectionMode.BLUETOOTH)
        } else {
            viewModel.setConnectionMode(mode) // WIFI or NONE
        }
    }
    // Background monitoring defaults on, so start it once per launch unless the
    // user has turned it off. The permission request is fired at the same time;
    // the service runs either way, the notification just stays hidden if denied.
    var backgroundStarted by remember { mutableStateOf(false) }
    LaunchedEffect(state.config.backgroundMonitoring) {
        if (state.config.backgroundMonitoring && !backgroundStarted) {
            backgroundStarted = true
            toggleBackground(true)
        }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    val onExportCsv: () -> Unit = { viewModel.exportCsv { loc -> toast("CSV saved to $loc") } }
    val onSavePng: () -> Unit = {
        val bmp = renderChartToBitmap(
            data = state.chart,
            visible = state.config.visiblePm,
            widthPx = 1600, heightPx = 900,
            background = pngBg, axisColor = pngAxis, gridColor = pngGrid, labelColor = pngLabel,
        )
        viewModel.savePng(bmp) { loc -> toast("PNG saved to $loc (check your gallery)") }
    }

    // Side-by-side on wide screens (Tab S7+, landscape); stacked on phones.
    // The inspection crosshair persists after the finger lifts, so it needs an
    // explicit dismissal: any touch on the dashboard outside the plot clears it.
    var cursor by remember { mutableStateOf<ChartCursor?>(null) }
    val chartData = state.chart
    val inspected = cursor?.epochIn(chartData)
    // Which anchor applies is decided by what the view is doing, and switching
    // between them must not move the crosshair: engaging Pin freezes the instant
    // it currently sits on, releasing it hands the cursor back to the place on
    // screen it currently occupies.
    val followsNow = chartData == null ||
        chartData.viewMode == ViewMode.LIVE || chartData.viewMode == ViewMode.LIVE_FROZEN
    LaunchedEffect(followsNow) {
        val here = cursor?.epochIn(chartData) ?: return@LaunchedEffect
        cursor = anchorFor(here, chartData, followsNow)
    }
    val onInspect: (Double?) -> Unit = { epoch ->
        cursor = epoch?.let { anchorFor(it, chartData, followsNow) }
    }
    val dismissModifier = if (cursor != null) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                // requireUnconsumed: the chart consumes touches that land inside
                // the plot, so only taps elsewhere reach here and dismiss.
                awaitFirstDown(requireUnconsumed = true)
                cursor = null
            }
        }
    } else {
        Modifier
    }

    val wide = LocalConfiguration.current.screenWidthDp >= 600
    if (wide) {
        WideDashboard(modifier.then(dismissModifier), state, viewModel, inspected,
            onInspect) { showOptions = true }
    } else {
        StackedDashboard(modifier.then(dismissModifier), state, viewModel, inspected,
            onInspect) { showOptions = true }
    }

    if (showYDialog) {
        val chart = state.chart
        YRangeDialog(
            initialMin = "%.1f".format(chart?.yMin ?: chart?.dataYMin ?: 0.0),
            initialMax = "%.1f".format(chart?.yMax ?: chart?.dataYMax ?: 10.0),
            onDismiss = { showYDialog = false },
            onApply = { lo, hi -> viewModel.setYRange(lo, hi); showYDialog = false },
        )
    }

    // A refused command is worth interrupting for: the user pressed something and
    // nothing happened, and the only useful next step is taking control.
    state.controlDenied?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissControlDenied,
            title = { Text("Someone else has control") },
            text = {
                Text(
                    "$message.\n\nYou can keep watching without taking control. " +
                        "Taking it over stops them from changing the sensor until " +
                        "they take it back."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.claimControl()
                }) { Text("Take control") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissControlDenied) { Text("Keep watching") }
            },
        )
    }

    if (showOptions) {
        OptionsSheet(
            state = state,
            viewModel = viewModel,
            onSetY = { showOptions = false; showYDialog = true },
            onToggleBackground = { on ->
                viewModel.setBackgroundMonitoring(on)
                toggleBackground(on)
            },
            onExportCsv = onExportCsv,
            onSavePng = onSavePng,
            onSelectMode = onSelectMode,
            onDismiss = { showOptions = false },
        )
    }
}

/** The lowest enabled threshold that asked to be drawn, or null if none did. */
/** "window 36h · 1305 pts · frozen" - drawn inside the plot. */
private fun DashboardUiState.viewInfo(): String {
    val span = chart?.spanSeconds ?: windowSeconds
    return "window ${formatWindow(span)}" +
        (chart?.let { "  ·  ${it.sampleCount} pts" } ?: "") +
        (chart?.takeIf { it.xFrozen }?.let { "  ·  frozen" } ?: "")
}

private fun DashboardUiState.alertRule() =
    config.alerts.filter { it.enabled && it.highlightPlot }.minByOrNull { it.threshold }

private fun DashboardUiState.alertThreshold(): Double? = alertRule()?.threshold

private fun DashboardUiState.alertField(): String = alertRule()?.field ?: "pm2_5"

/** Phone / portrait: everything stacked, chart taking the remaining height. */
@Composable
private fun StackedDashboard(
    modifier: Modifier,
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    inspected: Double?,
    onInspect: (Double?) -> Unit,
    onOpenOptions: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        StatusBanner(state)
        ReadoutCells(state, viewModel, Modifier.padding(top = 6.dp))
        InfoLine(state, onOpenOptions)
        ChartControls(state, viewModel)
        PmChart(
            data = state.chart,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
            visible = state.config.visiblePm,
            onZoom = viewModel::zoomBy,
            onPan = viewModel::panBy,
            onGestureStart = viewModel::beginGesture,
            onGestureEnd = viewModel::endGesture,
            onWidthChanged = viewModel::setChartWidthPx,
            inspectedEpoch = inspected,
            onInspect = onInspect,
            logY = state.config.logY,
            alertThreshold = state.alertThreshold(),
            alertField = state.alertField(),
            viewInfo = state.viewInfo(),
            timeZoneLabel = state.chart?.timeZoneLabel ?: "",
        )
    }
}

/** Tablet / landscape: a controls panel on the left, the chart filling the right. */
@Composable
private fun WideDashboard(
    modifier: Modifier,
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    inspected: Double?,
    onInspect: (Double?) -> Unit,
    onOpenOptions: () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        StatusBanner(state)
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.width(300.dp)) {
                ReadoutCells(state, viewModel)
                InfoLine(state, onOpenOptions)
                ChartControls(state, viewModel)
            }
            PmChart(
                data = state.chart,
                modifier = Modifier.fillMaxHeight().weight(1f),
                visible = state.config.visiblePm,
                onZoom = viewModel::zoomBy,
                onPan = viewModel::panBy,
                onGestureStart = viewModel::beginGesture,
                onGestureEnd = viewModel::endGesture,
                onWidthChanged = viewModel::setChartWidthPx,
                inspectedEpoch = inspected,
                onInspect = onInspect,
                logY = state.config.logY,
                alertThreshold = state.alertThreshold(),
                alertField = state.alertField(),
                viewInfo = state.viewInfo(),
                timeZoneLabel = state.chart?.timeZoneLabel ?: "",
            )
        }
    }
}

@Composable
private fun ReadoutCells(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
) {
    // PM1.0 and PM2.5 only. The SPS30 sizes particles optically below ~1.3um and
    // derives the coarser cuts from that distribution, so in clean air
    // pm2_5 == pm4_0 == pm10 exactly - three identical numbers read as a fault
    // rather than a result. All four remain on the chart and in exports.
    //
    // These double as the plot's series toggles. They were large panels showing
    // one number each, which is a poor trade for scarce main-screen height; as
    // buttons they earn the space, and putting show/hide here rather than buried
    // in the options menu matches where the user is looking when they want it.
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (field in listOf("pm1_0", "pm2_5")) {
            ReadoutCell(
                label = SeriesColors.label[field] ?: field,
                value = state.latest[field],
                color = SeriesColors.color[field] ?: Color.Gray,
                shown = field in state.config.visiblePm,
                style = state.config.readoutStyle,
                onClick = { viewModel.toggleSeries(field) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun InfoLine(state: DashboardUiState, onOpenOptions: () -> Unit) {
    // Show the actual visible span (so Live-frozen / Pin reflect the real window).
    val span = state.chart?.spanSeconds ?: state.windowSeconds
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The window and point count moved into the plot, where what they
        // describe actually is. This row is left holding the power pill and the
        // options gear.
        Spacer(Modifier.weight(1f))
        PowerPill(state)
        IconButton(onClick = onOpenOptions, modifier = Modifier.size(36.dp)) {
            Text("⚙", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * Sensor-host power at a glance: battery level when there is a battery, a plug
 * when on mains, and a warning when the host reports under-voltage.
 *
 * The under-voltage case earns its place because a weak supply is this project's
 * most expensive failure mode - it presents as the sensor never answering at all,
 * which looks like a wiring fault rather than a power one.
 */
@Composable
private fun PowerPill(state: DashboardUiState) {
    val power = state.power ?: return
    val lowPower = state.profile?.dutyCycling == true
    val warn = MaterialTheme.colorScheme.error
    val normal = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(end = 6.dp),
    ) {
        if (lowPower) {
            // Only meaningful once the sensor actually sleeps between readings.
            Text("🌙", style = MaterialTheme.typography.labelSmall, color = normal)
        }
        when {
            power.undervoltage -> {
                Text("⚠", style = MaterialTheme.typography.labelMedium, color = warn)
                Text("low volts", style = MaterialTheme.typography.labelSmall, color = warn)
            }
            power.onBattery -> {
                val pct = power.percent
                val tint = if ((pct ?: 100.0) < 15) warn else normal
                BatteryIcon(percent = pct, charging = power.charging, tint = tint)
                pct?.let {
                    Text(
                        "${it.roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                    )
                }
            }
            // Mains: a charging battery, which is what "plugged in" means here.
            else -> BatteryIcon(percent = null, charging = true, tint = normal)
        }
    }
}

/**
 * A battery outline with an optional fill level and charging bolt.
 *
 * Drawn rather than using a Material icon so it can be sized freely and so the
 * app does not have to pull in material-icons-extended for one glyph. The plug
 * emoji it replaces was both too small to read and ambiguous.
 */
@Composable
private fun BatteryIcon(
    percent: Double?,
    charging: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(width = 30.dp, height = 16.dp)) {
        val capW = size.width * 0.08f
        val bodyW = size.width - capW
        val stroke = size.height * 0.11f
        val r = size.height * 0.16f

        // Body outline.
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyW - stroke, size.height - stroke),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = stroke),
        )
        // Positive terminal cap.
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyW, size.height * 0.3f),
            size = Size(capW, size.height * 0.4f),
            cornerRadius = CornerRadius(r / 2f, r / 2f),
        )
        // Charge level, when known.
        if (percent != null) {
            val inset = stroke * 1.6f
            val trackW = bodyW - inset * 2f
            val fill = (percent / 100.0).coerceIn(0.0, 1.0).toFloat()
            if (fill > 0f) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(inset, inset),
                    size = Size(trackW * fill, size.height - inset * 2f),
                    cornerRadius = CornerRadius(r / 2f, r / 2f),
                )
            }
        }
        if (charging) {
            // Lightning bolt, centred on the body. Drawn over any fill, in the
            // background colour where a fill exists so it stays legible.
            val cx = bodyW / 2f
            val cy = size.height / 2f
            val h = size.height * 0.62f
            val w = size.width * 0.16f
            val bolt = Path().apply {
                moveTo(cx + w * 0.35f, cy - h / 2f)
                lineTo(cx - w * 0.75f, cy + h * 0.12f)
                lineTo(cx - w * 0.05f, cy + h * 0.12f)
                lineTo(cx - w * 0.35f, cy + h / 2f)
                lineTo(cx + w * 0.85f, cy - h * 0.10f)
                lineTo(cx + w * 0.10f, cy - h * 0.10f)
                close()
            }
            drawPath(bolt, tint)
        }
    }
}

@Composable
private fun ChartControls(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
) {
    val chart = state.chart
    val viewMode = chart?.viewMode ?: ViewMode.LIVE
    val isPin = viewMode == ViewMode.PIN
    val isAll = viewMode == ViewMode.ALL
    val isLive = viewMode == ViewMode.LIVE

    // Pin and Auto stay put while the window presets scroll. They are the two
    // controls a user reaches for most, and having Pin slide off to the left
    // reads as the button having disappeared rather than merely scrolled.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LongPressChip(
            selected = isPin,
            label = "Pin",
            onClick = viewModel::onPinTap,
            onLongClick = viewModel::onPinLongPress,
        )
        FilterChip(
            selected = isAll && chart?.autoInterval == true,
            onClick = viewModel::autoSet,
            label = { Text("Auto") },
        )
        ScrollingChips(
            modifier = Modifier.weight(1f),
        ) {
            for (preset in state.config.windowPresets) {
                if (preset.seconds >= com.sensirion.sps30.data.AppConfig.ALL_SECONDS) continue
                val selected = isLive && chart != null &&
                    kotlin.math.abs(chart.windowSeconds - preset.seconds) < preset.seconds * 0.02
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.onWindow(preset.seconds) },
                    label = { Text(preset.label) },
                )
            }
        }
    }
    RowLegend()
    AveragingControls(state, viewModel)
}

/**
 * A horizontally scrolling chip strip with a fade at its right edge.
 *
 * The fade is the only cue that there is more to the right; without it a user
 * who has added several window presets has no way to tell the row continues
 * past the screen edge.
 */
@Composable
private fun ScrollingChips(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    val fade = MaterialTheme.colorScheme.surface
    Box(modifier = modifier) {
        Row(
            modifier = Modifier.horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
        if (scroll.canScrollForward) {
            // matchParentSize, not fillMaxHeight: the latter expands to the
            // *available* height, which in a Column where the chart takes
            // weight(1f) is the whole screen - it inflated the control row,
            // shoved "avg"/"raw" into the middle of the display and squashed the
            // chart to a sliver. matchParentSize takes the box's measured size
            // without contributing to it.
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(24.dp)
                        .background(
                            Brush.horizontalGradient(listOf(Color.Transparent, fade)),
                        ),
                )
            }
        }
    }
}

/**
 * Labels the two button rows from the space between them.
 *
 * The rows answer different questions - how much time is on screen, versus how
 * finely it is drawn - and neither is self-evident from the buttons alone.
 * Putting the captions between the rows rather than beside them leaves both rows
 * the full width, which they need once presets start accumulating.
 */
@Composable
private fun RowLegend() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        Text("▲ window", style = MaterialTheme.typography.labelSmall, color = tint)
        Text("average ▼", style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/**
 * How much time each plotted point averages over.
 *
 * "raw" is pinned on the left - it is the way back to unaveraged data and must
 * never scroll out of reach. Whatever Auto last worked out sits immediately
 * beside it as a button of its own, so the figure it chose is both visible and
 * one tap away after a look at raw.
 */
@Composable
private fun AveragingControls(state: DashboardUiState, viewModel: DashboardViewModel) {
    val chart = state.chart
    val bucket = chart?.bucketSeconds
    val autoChosen = chart?.autoChosenSeconds
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = bucket == null,
            onClick = { viewModel.setPlotInterval(null) },
            label = { Text("raw") },
        )
        if (autoChosen != null) {
            FilterChip(
                selected = bucket == autoChosen,
                onClick = { viewModel.setPlotInterval(autoChosen) },
                label = { Text(formatAveraging(autoChosen)) },
            )
        }
        ScrollingChips(modifier = Modifier.weight(1f)) {
            for (seconds in state.config.plotIntervals) {
                if (seconds <= 1.0 || seconds == autoChosen) continue
                FilterChip(
                    selected = bucket == seconds,
                    onClick = { viewModel.setPlotInterval(seconds) },
                    label = { Text(formatAveraging(seconds)) },
                )
            }
        }
    }
}

/** Whole units only - a fractional averaging window is noise in a button. */
private fun formatAveraging(seconds: Double): String = when {
    seconds < 120 -> "${seconds.toInt()}s"
    seconds < 7200 -> "${(seconds / 60).toInt()}m"
    else -> "${(seconds / 3600).toInt()}h"
}

/** A chip that supports both a tap and a long-press (used for Pin). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) scheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        border = BorderStroke(1.dp, if (selected) scheme.secondaryContainer else scheme.outline),
        modifier = Modifier
            .height(32.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun YRangeDialog(
    initialMin: String,
    initialMax: String,
    onDismiss: () -> Unit,
    onApply: (Double, Double) -> Unit,
) {
    var minText by remember { mutableStateOf(initialMin) }
    var maxText by remember { mutableStateOf(initialMax) }
    val lo = minText.toDoubleOrNull()
    val hi = maxText.toDoubleOrNull()
    val valid = lo != null && hi != null && lo < hi
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Y range (${SeriesColors.unit})") },
        text = {
            Column {
                OutlinedTextField(minText, { minText = it }, label = { Text("min") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(maxText, { maxText = it }, label = { Text("max") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onApply(lo!!, hi!!) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StatusBanner(state: DashboardUiState) {
    val (text, bg, fg) = bannerText(state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        state.sensor?.let {
            Text(
                text = "SPS30 · fw ${it.firmware}",
                color = fg,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/**
 * The daemon state, with a countdown when the phase has a known length.
 *
 * Low-power mode spends most of its time doing nothing on purpose, so a bare
 * "sleeping" gives no clue whether the thing is working or wedged. The remaining
 * time is what makes the wait legible.
 */
@Composable
private fun phaseLabel(state: DashboardUiState, daemonState: String): String {
    @Suppress("UNUSED_EXPRESSION") state.clockTick // re-read each tick to recompose
    val name = when (daemonState) {
        Wire.STATE_WARMUP -> "warming up"
        Wire.STATE_SLEEPING -> "asleep"
        Wire.STATE_MEASURING ->
            if (state.profile?.dutyCycling == true) "averaging" else "measuring"
        else -> daemonState
    }
    val endsAt = state.phaseEndsAtMs ?: return name
    val remaining = ((endsAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
    return "$name ${formatRemaining(remaining)}"
}

/** "8s", "1:23", "14:05" - a countdown, not a duration. */
private fun formatRemaining(seconds: Long): String =
    if (seconds < 60) "${seconds}s" else "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * What the sensor was doing when we last heard from it, phrased for the
 * disconnected banner.
 *
 * Said while the link is down, so every branch has to be true of a sensor we can
 * no longer see. That rules out the confident "still recording" this once used
 * for every case: measurement can be off, or the fan can be self-cleaning, and
 * claiming otherwise sends the user to look for a fault that is not there.
 * An unknown state gets no claim at all rather than an optimistic guess.
 */
internal fun lastKnownSensorActivity(daemonState: String?): String = when (daemonState) {
    Wire.STATE_MEASURING, Wire.STATE_WARMUP, Wire.STATE_SLEEPING ->
        "the sensor is still recording"
    Wire.STATE_PAUSED -> "measurement was off at the sensor"
    Wire.STATE_CLEANING -> "the sensor was cleaning its fan"
    Wire.STATE_CHECKING -> "the sensor host was still looking for the sensor"
    Wire.STATE_SHUTDOWN -> "the sensor was shutting down"
    else -> "not connected to the sensor"
}

/** Banner text with a background and a foreground that is legible on it. */
private data class BannerStyle(val text: String, val background: Color, val foreground: Color)

/** The scheme's matching "on" colour for one of the roles the banner uses. */
private fun onColorFor(scheme: androidx.compose.material3.ColorScheme, bg: Color): Color = when (bg) {
    scheme.primary -> scheme.onPrimary
    scheme.secondary -> scheme.onSecondary
    scheme.tertiary -> scheme.onTertiary
    scheme.error -> scheme.onError
    else -> scheme.onSurface
}

@Composable
private fun bannerText(state: DashboardUiState): BannerStyle {
    val scheme = MaterialTheme.colorScheme
    // Each background gets its matching "on" colour rather than always onPrimary,
    // which was legible on the primary banner and marginal on the others.
    fun style(text: String, bg: Color) = BannerStyle(text, bg, onColorFor(scheme, bg))
    // A host that announced its own shutdown outranks every other banner, including
    // the reconnect churn that follows it. The user watched the fan stop; a banner
    // saying "reconnecting" or "still recording" would be describing a machine that
    // is off. This clears itself the moment the host says hello again.
    if (state.sensorShutDown) {
        return style("Sensor shut down · safe to unplug · not recording", scheme.secondary)
    }
    // Lead every banner with the transport type so the connection is never ambiguous:
    // "Bluetooth (BLE)" over BLE, "Wi-Fi" over the network/TCP path.
    // Disconnected is a state the user chose, not a fault - say so plainly, and say
    // what it does NOT mean, because the obvious worry is that recording stopped.
    // What it does not mean depends on what the sensor was last seen doing: this
    // used to assert "still recording" unconditionally, which was a leftover from
    // when recording was the only thing it could be doing.
    if (state.config.connectionMode == ConnectionMode.NONE) {
        return style("Disconnected · ${lastKnownSensorActivity(state.daemonState)}", scheme.secondary)
    }
    val via = if (state.config.connectionMode == ConnectionMode.BLUETOOTH) "Bluetooth (BLE)" else "Wi-Fi"
    if (state.discovering) return style("$via · searching for the daemon…", scheme.tertiary)
    if (state.discoveryFailed) {
        // A VPN that captures LAN traffic cannot be routed around from inside the
        // app - Android forbids it - so the only honest thing is to name it as a
        // likely cause rather than leaving the user to guess.
        val hint = if (WifiNetworkBinder.vpnActive(LocalContext.current)) {
            " A VPN may be blocking local network access - try turning it off, or use Bluetooth."
        } else {
            " Check the host and radio."
        }
        return style("$via · no sensor found.$hint", scheme.error)
    }
    return when (val c = state.connection) {
        is ConnectionState.Connected -> {
            val st = state.daemonState?.let { " · ${phaseLabel(state, it)}" } ?: ""
            style("$via · connected ${c.endpoint}$st", scheme.primary)
        }
        is ConnectionState.Connecting -> style("$via · connecting to ${c.endpoint}…", scheme.tertiary)
        is ConnectionState.Reconnecting -> style("$via · reconnecting…", scheme.error)
        ConnectionState.Idle -> style("$via · starting…", scheme.tertiary)
    }
}

@Composable
private fun ReadoutCell(
    label: String,
    value: Double?,
    color: Color,
    shown: Boolean,
    style: com.sensirion.sps30.data.ReadoutStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two decimals, deliberately finer than the sensor's absolute accuracy of
    // +-(5 ug/m3 + 5%). The tile is not claiming that precision about the air:
    // it is showing that the reading is alive. Consecutive samples move by about
    // 0.02 ug/m3, so a single decimal holds the same number for long stretches
    // while the plot's newest point visibly moves - which reads as a frozen or
    // lagging display. What the sensor does resolve well is *change*, and this
    // is where the user looks for it.
    val reading = value?.let { "%.2f".format(it) } ?: "--"
    // The ring is the selection state: it says both "this trace is on the plot"
    // and "this is the colour to look for", so the tiles double as the chart's
    // legend without one being drawn. Shared by both layouts.
    val tile = modifier
        .clip(RoundedCornerShape(12.dp))
        .background(
            if (shown) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        )
        .border(
            // The unselected ring uses `outline` rather than `outlineVariant`:
            // the fainter tone sat so close to the tile's own fill that the "off"
            // state barely read as a button at all, while the "on" state was
            // unmistakable. Both states need to look deliberate.
            width = if (shown) 2.dp else 1.5.dp,
            color = if (shown) color else MaterialTheme.colorScheme.outline,
            shape = RoundedCornerShape(12.dp),
        )
        .clickable(onClick = onClick)

    val labelColor = if (shown) color else MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = if (shown) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant
    // Everything is bold: at these sizes a regular weight goes thin against its
    // neighbours and reads as frail rather than as deliberately quieter. The
    // unit's demotion is carried by its light grey alone.
    val unitColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)

    when (style) {
        com.sensirion.sps30.data.ReadoutStyle.STACKED -> Column(
            modifier = tile.padding(vertical = 5.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // lineHeight pinned close to the font size throughout. Default
            // leading is generous - sensible for paragraphs, wasteful for three
            // single-line labels stacked on top of each other, where it was
            // adding well over half the tile's height in empty space.
            Text(
                label,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                color = labelColor,
                maxLines = 1,
            )
            Text(
                reading,
                // Tabular figures: proportional digits shift the number sideways
                // as it updates once a second, and that wobble is what makes a
                // live readout tiring rather than glanceable.
                style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                fontSize = if (reading.length > 4) 24.sp else 28.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
            )
            Text(
                SeriesColors.unit,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold,
                color = unitColor,
                maxLines = 1,
            )
        }

        com.sensirion.sps30.data.ReadoutStyle.INLINE -> Row(
            // Less padding vertically than horizontally: the number's line box
            // carries ascender and descender space it never fills, so equal
            // padding would push the tile taller than the glyphs justify.
            modifier = tile.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The unit is anchored to the right edge and the label-plus-reading
            // pair is centred in everything left of it. Laid out as a plain row,
            // a change in the reading's width shifted its neighbours around, so
            // the number drifted as digits came and went and the two tiles
            // stopped lining up with each other - which is exactly what makes
            // them comparable at a glance.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = labelColor, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        reading,
                        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                        // Sized down only for the rare wide reading. Smoke events
                        // reach the hundreds, and a readout that clips its own
                        // number when it matters most is a poor trade.
                        fontSize = when (reading.length) {
                            in 0..4 -> 32.sp
                            5 -> 26.sp
                            else -> 21.sp
                        },
                        fontWeight = FontWeight.Bold,
                        color = valueColor,
                        maxLines = 1,
                    )
                }
            }
            Text(
                SeriesColors.unit,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = unitColor,
                maxLines = 1,
                // Pulled in off the right edge so the tile reads as one group.
                modifier = Modifier.padding(end = 11.dp),
            )
        }
    }
}

private fun formatWindow(seconds: Double): String {
    val s = seconds.toLong()
    return when {
        s % 3600 == 0L && s >= 3600 -> "${s / 3600}h"
        s % 60 == 0L && s >= 60 -> "${s / 60}m"
        else -> "${s}s"
    }
}


/**
 * What the inspection crosshair is anchored to.
 *
 * Which one is right depends on what the plot is doing, and both behaviours are
 * wanted:
 *
 *  - [AtTime] - a pinned view is showing a particular stretch of the past, so
 *    the cursor holds its **instant**. As the window grows it travels leftwards
 *    with its own data point, staying on the event the user put it on.
 *  - [AtPlace] - a live view is showing the present arriving, so the cursor
 *    holds its **place on the plot** and the data flows underneath it. The
 *    readout then keeps updating at a fixed age - park it at the right edge and
 *    it reports each new sample as it lands, the way `less +F` follows a file
 *    still being written.
 */
sealed interface ChartCursor {
    /** Fixed to an instant; moves across the plot as the range changes. */
    data class AtTime(val epoch: Double) : ChartCursor

    /** Fixed to a position across the plot, as a fraction of the visible span. */
    data class AtPlace(val fraction: Double) : ChartCursor
}

/** The epoch the cursor currently sits on, given what is on screen. */
fun ChartCursor.epochIn(data: ChartData?): Double? = when (this) {
    is ChartCursor.AtTime -> epoch
    is ChartCursor.AtPlace ->
        data?.let { it.start + fraction * (it.end - it.start) }
}

/** Anchor [epoch] the way the current view mode calls for, without moving it. */
fun anchorFor(epoch: Double, data: ChartData?, followsNow: Boolean): ChartCursor =
    if (followsNow && data != null && data.end > data.start) {
        ChartCursor.AtPlace(((epoch - data.start) / (data.end - data.start)).coerceIn(0.0, 1.0))
    } else {
        ChartCursor.AtTime(epoch)
    }
