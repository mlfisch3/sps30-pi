package com.sensirion.sps30.ui

import android.content.Context
import com.sensirion.sps30.data.AlertEngine
import com.sensirion.sps30.data.AlertRule
import com.sensirion.sps30.data.AppConfig
import com.sensirion.sps30.data.ChartData
import com.sensirion.sps30.data.ChartView
import com.sensirion.sps30.data.ConnectionMode
import com.sensirion.sps30.config.StoredSessions
import com.sensirion.sps30.data.SessionBackup
import com.sensirion.sps30.data.StoredImport
import com.sensirion.sps30.data.TimeSeriesHistory
import com.sensirion.sps30.data.ViewMode
import com.sensirion.sps30.net.BleTransport
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.net.TcpTransport
import com.sensirion.sps30.net.Transport
import com.sensirion.sps30.net.WifiNetworkBinder
import com.sensirion.sps30.service.AlertNotifier
import com.sensirion.sps30.service.Sps30Service
import com.sensirion.sps30.wear.RelayPolicy
import com.sensirion.sps30.wear.WearRelay
import com.sensirion.sps30.wire.Commands
import com.sensirion.sps30.wire.ServerMessage
import com.sensirion.sps30.wire.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.format.DateTimeFormatter

data class SensorInfo(val product: String, val serial: String, val firmware: String)

data class DashboardUiState(
    val connection: ConnectionState = ConnectionState.Idle,
    val discovering: Boolean = false,
    val discoveryFailed: Boolean = false,
    val sensor: SensorInfo? = null,
    val daemonState: String? = null,
    val daemonDetail: String = "",
    /**
     * Latched when the daemon announced it was powering off, and cleared only by
     * hearing a live state from it again.
     *
     * [daemonState] alone cannot carry this: the shutdown announcement is the last
     * thing the host sends, so a moment later the link drops and every ordinary
     * reading of the UI ("reconnecting", "disconnected, still recording") describes
     * a sensor that no longer exists. The user watched the fan stop; the app must
     * not then claim it is recording.
     */
    val sensorShutDown: Boolean = false,
    /** Wall-clock millis at which the current daemon phase ends, when known. */
    val phaseEndsAtMs: Long? = null,
    val latest: Map<String, Double> = emptyMap(),
    val latestTimestamp: String? = null,
    val chart: ChartData? = null,
    val windowSeconds: Double = 300.0,
    val backgroundEnabled: Boolean = false,
    val config: AppConfig = AppConfig(),
    /** The daemon's active acquisition profile, once it has told us. */
    val profile: ServerMessage.AcquisitionProfile? = null,
    val power: ServerMessage.PowerStatus? = null,
    /** False if the sensor firmware cannot sleep, so low-power must be offered. */
    val supportsSleep: Boolean = true,
    /** Advances once a second; only there to drive countdown recomposition. */
    val clockTick: Int = 0,
    /** Ids of alert rules currently breached, for painting the UI. */
    val activeAlerts: Set<String> = emptySet(),
    /** This connection's roster id, so it can recognise itself as the holder. */
    val clientId: Int? = null,
    /** Who currently holds the control lock; null means nobody does. */
    val controlHolder: Int? = null,
    val controlHolderName: String? = null,
    /** Everyone connected to the sensor right now. */
    val watchers: List<ServerMessage.Watcher> = emptyList(),
    val maxClients: Int = 0,
    /** Set when a command was refused because someone else holds control. */
    val controlDenied: String? = null,
    /** Progress of copying the sensor's recorded sessions to this device. */
    val backup: SessionBackup = SessionBackup.Idle,
    /** Progress of reading those stored sessions back into the plot. */
    val storedImport: StoredImport = StoredImport.Idle,
) {
    /** True when nobody else is driving, so the controls are live. */
    val hasControl: Boolean
        get() = controlHolder == null || controlHolder == clientId
}

/**
 * Process-lifetime owner of the live connection, history, and chart view. A
 * singleton so the stream survives Activity recreation and can be shared with the
 * foreground service. It runs whichever [Transport] the configured
 * [ConnectionMode] selects (Wi-Fi/TCP or Bluetooth-LE) - the message-folding /
 * history / chart code is transport-agnostic because both emit the same
 * [ServerMessage]s.
 *
 * All non-thread-safe model mutation is confined to a single-threaded dispatcher,
 * so the message collector, the 1 Hz recompute ticker, and UI control calls never
 * race.
 */
object MonitorSession {

    private const val PORT = Wire.DEFAULT_PORT
    private val CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    private const val SPARK_WINDOW_SECONDS = 3600.0
    private const val SPARK_MAX_POINTS = 48

    /** Push at least this often even if nothing moved, so the watch never crosses
     *  its own staleness threshold (10 min) and greys the reading out. */
    private const val HEARTBEAT_MS = 300_000L

    // Point budget bounds, before the chart has reported its width.
    private const val MIN_POINTS = 120
    private const val MAX_POINTS = 2000
    private const val DEFAULT_POINTS = 600

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val confinement = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confinement)

    private val history = TimeSeriesHistory(retentionSeconds = 172_800.0)
    private val chart = ChartView(windowSeconds = 300.0)

    private val _ui = MutableStateFlow(DashboardUiState(windowSeconds = 300.0))
    val ui: StateFlow<DashboardUiState> = _ui.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var transport: Transport? = null
    private var connectionJob: Job? = null
    private var currentMode: ConnectionMode? = null
    @Volatile private var started = false

    /** Start the recompute ticker once per process (idempotent). The connection is
     *  started by the first [applyConfig], which knows the persisted mode. */
    fun ensureStarted(context: Context) {
        appContext = context.applicationContext
        // Held for the process lifetime: requestNetwork is not cheap, and losing
        // the binding between reconnects would drop it exactly when a flaky link
        // needs it most.
        WifiNetworkBinder.start(context)
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        // While a gesture is in flight the flush loop is already recomputing at
        // its own pace; a live tick on top of that is pure contention.
        scope.launch {
            while (isActive) {
                if (!gestureActive) recompute()
                tickClock()
                delay(1000)
            }
        }
    }

    /** (Re)start the connection using [mode]'s transport, replacing any current one.
     *  [ConnectionMode.NONE] tears the link down and leaves it down. */
    private fun startConnection(mode: ConnectionMode) {
        connectionJob?.cancel()
        transport?.stop() // force-close the old link now (TCP blocking reads ignore cancel)
        transport = null
        currentMode = mode
        if (mode == ConnectionMode.NONE) {
            // Keep the plotted history on screen - the sensor is still recording and
            // reconnecting will backfill whatever was missed.
            _ui.update { it.copy(connection = ConnectionState.Idle, discovering = false) }
            appContext?.let { Sps30Service.stop(it) }
            _ui.update { it.copy(backgroundEnabled = false) }
            return
        }
        val t = createTransport(mode) ?: return
        transport = t
        connectionJob = scope.launch {
            val stateJob = launch { t.state.collect { st -> _ui.update { it.copy(connection = st) } } }
            val msgJob = launch { t.messages.collect { onMessage(it) } }
            try {
                while (isActive) {
                    try {
                        t.run() // long-running with its own reconnect; returns/throws on setup failure
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _ui.update { it.copy(connection = ConnectionState.Reconnecting(e.message ?: "connection lost")) }
                    }
                    if (!isActive) break
                    delay(1500)
                }
            } finally {
                stateJob.cancel(); msgJob.cancel()
            }
        }
    }

    private fun createTransport(mode: ConnectionMode): Transport? = when (mode) {
        ConnectionMode.WIFI -> TcpTransport(PORT)
        ConnectionMode.BLUETOOTH -> appContext?.let { BleTransport(it) }
        ConnectionMode.NONE -> null // handled before this is reached
    }

    fun setBackgroundEnabled(enabled: Boolean) {
        _ui.update { it.copy(backgroundEnabled = enabled) }
    }

    private fun onMessage(msg: ServerMessage) {
        when (msg) {
            is ServerMessage.Hello -> {
                _ui.update {
                    it.copy(
                        sensor = SensorInfo(msg.product, msg.serial, msg.firmware),
                        daemonState = msg.state,
                        daemonDetail = msg.detail,
                        // A hello from a host that is up clears the shutdown latch:
                        // it was power-cycled, which is the only way back.
                        sensorShutDown = msg.state == Wire.STATE_SHUTDOWN,
                        phaseEndsAtMs = msg.phaseSeconds?.let { s ->
                            System.currentTimeMillis() + (s * 1000).toLong()
                        },
                        profile = msg.profile ?: it.profile,
                        power = msg.power ?: it.power,
                        supportsSleep = msg.supportsSleep,
                        clientId = msg.clientId ?: it.clientId,
                        maxClients = if (msg.maxClients > 0) msg.maxClients else it.maxClients,
                    )
                }
                // Name ourselves so other users see a person, not an IP address.
                transport?.send(Commands.identify(deviceName()))
                requestBackfill(msg.csv)
            }

            is ServerMessage.AcquisitionProfile -> _ui.update { it.copy(profile = msg) }

            is ServerMessage.Control -> _ui.update {
                it.copy(
                    controlHolder = msg.holder,
                    controlHolderName = msg.holderName,
                    // Clear a stale refusal once control actually moves.
                    controlDenied = if (msg.holder == null || msg.holder == it.clientId) {
                        null
                    } else {
                        it.controlDenied
                    },
                )
            }

            is ServerMessage.Clients -> _ui.update {
                it.copy(watchers = msg.clients, maxClients = msg.max)
            }

            is ServerMessage.ErrorMsg -> {
                if (msg.needsControl) _ui.update { it.copy(controlDenied = msg.message) }
                if (_ui.value.backup is SessionBackup.Requested) {
                    failBackup(msg.message)
                }
            }

            is ServerMessage.BackupMeta -> onBackupMeta(msg)

            is ServerMessage.BackupChunk -> onBackupChunk(msg)

            is ServerMessage.PowerStatus -> _ui.update { it.copy(power = msg) }

            is ServerMessage.Reading -> {
                history.add(msg)
                _ui.update { it.copy(latest = msg.values, latestTimestamp = msg.timestamp) }
                checkAlerts(msg.values)
                maybeRelayToWatch(msg.values, msg.timestamp)
                recompute()
            }

            is ServerMessage.StateMsg -> _ui.update {
                it.copy(
                    daemonState = msg.state,
                    daemonDetail = msg.detail,
                    sensorShutDown = msg.state == Wire.STATE_SHUTDOWN,
                    phaseEndsAtMs = msg.phaseSeconds?.let { s ->
                        System.currentTimeMillis() + (s * 1000).toLong()
                    },
                )
            }

            // Backfill: rows we missed while (re)connecting or switching transport,
            // read from the Pi's master CSV. De-dup by (csv, index) makes overlap safe.
            is ServerMessage.FileData -> {
                if (history.addRows(msg.name, msg.columns, msg.rows) > 0) recompute()
            }

            else -> Unit
        }
    }

    /**
     * On (re)connect the daemon's Hello names the current CSV and its latest index.
     * Ask it for everything past our local high-water mark for that file, so a
     * transport switch or a dropped link leaves no gap in the plotted history. A
     * fresh file (daemon restarted) has no local rows, so nothing is requested.
     */
    private fun requestBackfill(csv: String?) {
        val name = csv ?: return
        if (name.isEmpty()) return
        // Always backfill the current file: fill any gap missed while disconnected
        // or backgrounded. since=0 (no local rows yet) fetches the whole current
        // session so the plot reflects everything the Pi recorded. The only Pi data
        // that legitimately never appears is pre-Restart or flagged.
        transport?.send(Commands.getFile(name, history.maxIndexFor(name)))
    }

    // --- watch relay (phone -> Galaxy Watch 7 over the Wearable Data Layer) ----
    @Volatile private var lastRelayMs = 0L
    private var lastRelayed: Map<String, Double> = emptyMap()

    /**
     * Rate-limited push of the latest reading + a 1h PM2.5 sparkline to the watch.
     * Runs on the model dispatcher (called from [onMessage]) so [history] access is
     * safe.
     *
     * [RelayPolicy] decides whether a push is warranted; see it for why.
     */
    private fun maybeRelayToWatch(values: Map<String, Double>, timestamp: String?) {
        val ctx = appContext ?: return
        val nowMs = System.currentTimeMillis()
        val warranted = RelayPolicy.shouldPublish(
            nowMs = nowMs,
            lastPublishMs = lastRelayMs,
            minGapMs = _ui.value.config.watchRelaySeconds * 1000L,
            heartbeatMs = HEARTBEAT_MS,
            lastPublished = lastRelayed,
            current = values,
        )
        if (!warranted) return

        lastRelayMs = nowMs
        lastRelayed = Wire.PM_FIELDS.associateWith { values[it] ?: Double.NaN }
        WearRelay.publish(
            ctx, values, timestamp, buildSpark(), nowMs,
            relaySeconds = _ui.value.config.watchRelaySeconds,
        )
    }

    /** Last-hour PM2.5 values (oldest -> newest), evenly decimated to at most
     *  [SPARK_MAX_POINTS] so the Data Layer payload stays tiny. */
    private fun buildSpark(): FloatArray {
        val cutoff = currentNow() - SPARK_WINDOW_SECONDS
        val pm = ArrayList<Float>()
        for (s in history.snapshot()) {
            if (s.epoch < cutoff) continue
            val v = s.values["pm2_5"] ?: continue
            pm.add(v.toFloat())
        }
        if (pm.size <= SPARK_MAX_POINTS) return pm.toFloatArray()
        val step = (pm.size - 1).toDouble() / (SPARK_MAX_POINTS - 1)
        return FloatArray(SPARK_MAX_POINTS) { pm[Math.round(it * step).toInt()] }
    }

    /** Plot width in px, reported by the chart. Drawing more points than there are
     *  pixels is invisible work, so this caps what [recompute] extracts. */
    @Volatile private var chartWidthPx = 0

    fun setChartWidthPx(width: Int) { chartWidthPx = width }

    private val pointBudget: Int
        get() = chartWidthPx.takeIf { it > 0 }?.coerceIn(MIN_POINTS, MAX_POINTS) ?: DEFAULT_POINTS

    private fun currentNow(): Double {
        val wallNow = System.currentTimeMillis() / 1000.0
        return maxOf(wallNow, history.latestEpoch() ?: wallNow)
    }

    /** Bumped once a second so a countdown banner re-renders while the sensor
     *  sleeps and no readings are arriving to drive recomposition. */
    private fun tickClock() {
        if (_ui.value.phaseEndsAtMs != null) {
            _ui.update { it.copy(clockTick = it.clockTick + 1) }
        }
    }

    private fun recompute() {
        // "all" tracks the *current* session dynamically: re-derive its origin from
        // the earliest stable reading each tick, so it self-corrects after a Restart
        // (which clears history and starts a new session) with no need to re-tap.
        if (chart.viewMode() == ViewMode.ALL) {
            chart.applyAll(history.firstStableEpoch() ?: currentNow(), currentNow())
        }
        val data = chart.compute(
            history, currentNow(), maxPoints = pointBudget,
            excludeFlagged = _ui.value.config.excludeFlagged,
        )
        _ui.update { it.copy(chart = data, windowSeconds = chart.windowSeconds) }
    }

    // --- copying recorded sessions to this device ----------------------------
    //
    // Chunks are appended to a temp file as they arrive rather than accumulated
    // in memory: a sensor left running for weeks holds hundreds of megabytes of
    // sessions, and buffering that on the heap would be a needless way to fail.

    private var backupFile: java.io.File? = null
    private var backupSink: java.io.OutputStream? = null
    private var backupName: String = ""
    private var backupExpectedSeq = 0

    /** Ask the sensor for every session it has recorded. Needs no control claim. */
    fun copySessionsToThisDevice() {
        if (_ui.value.backup is SessionBackup.Receiving) return
        closeBackupSink()
        _ui.update { it.copy(backup = SessionBackup.Requested) }
        sendCommand(Commands.backup())
    }

    fun dismissBackupResult() {
        val current = _ui.value.backup
        if (current is SessionBackup.Saved || current is SessionBackup.Failed) {
            _ui.update { it.copy(backup = SessionBackup.Idle) }
        }
    }

    private fun onBackupMeta(msg: ServerMessage.BackupMeta) {
        val ctx = appContext ?: return
        try {
            closeBackupSink()
            val file = java.io.File.createTempFile("sps30-backup", ".zip", ctx.cacheDir)
            backupFile = file
            backupSink = java.io.BufferedOutputStream(file.outputStream(), 64 * 1024)
            backupName = msg.name
            backupExpectedSeq = 0
            _ui.update {
                it.copy(
                    backup = SessionBackup.Receiving(0, msg.bytes, msg.sessions, msg.rows),
                )
            }
        } catch (e: Exception) {
            failBackup("could not open a file to receive into: ${e.message}")
        }
    }

    private fun onBackupChunk(msg: ServerMessage.BackupChunk) {
        val state = _ui.value.backup as? SessionBackup.Receiving ?: return
        val sink = backupSink ?: return
        // A gap means a chunk was lost in transit. Better to fail loudly than to
        // hand someone a corrupt archive they believe is a backup.
        if (msg.seq != backupExpectedSeq) {
            failBackup("transfer lost data (expected chunk ${backupExpectedSeq}, got ${msg.seq})")
            return
        }
        backupExpectedSeq++
        try {
            var received = state.received
            if (msg.data.isNotEmpty()) {
                val bytes = android.util.Base64.decode(msg.data, android.util.Base64.DEFAULT)
                sink.write(bytes)
                received += bytes.size
                _ui.update { it.copy(backup = state.copy(received = received)) }
            }
            if (msg.done) finishBackup(state.copy(received = received))
        } catch (e: Exception) {
            failBackup("could not write the archive: ${e.message}")
        }
    }

    private fun finishBackup(state: SessionBackup.Receiving) {
        val ctx = appContext ?: return
        val file = backupFile ?: return
        try {
            backupSink?.flush()
            backupSink?.close()
            backupSink = null
            if (state.total > 0 && file.length() != state.total) {
                failBackup("archive is incomplete (${file.length()} of ${state.total} bytes)")
                return
            }
            val location = com.sensirion.sps30.config.Exports.writeArchive(ctx, file, backupName)
            _ui.update {
                it.copy(
                    backup = SessionBackup.Saved(
                        location = location,
                        bytes = file.length(),
                        sessions = state.sessions,
                        rows = state.rows,
                    ),
                )
            }
        } catch (e: Exception) {
            failBackup("could not save the archive: ${e.message}")
        } finally {
            file.delete()
            backupFile = null
        }
    }

    private fun failBackup(reason: String) {
        closeBackupSink()
        _ui.update { it.copy(backup = SessionBackup.Failed(reason)) }
    }

    private fun closeBackupSink() {
        runCatching { backupSink?.close() }
        backupSink = null
        backupFile?.delete()
        backupFile = null
    }

    // --- multi-user control --------------------------------------------------

    /** Take the control lock, including from a holder who has walked away. */
    fun claimControl() {
        _ui.update { it.copy(controlDenied = null) }
        sendCommand(Commands.claim())
    }

    fun releaseControl() = sendCommand(Commands.release())

    fun dismissControlDenied() = _ui.update { it.copy(controlDenied = null) }

    /** A name other users will recognise. Falls back to the model name. */
    private fun deviceName(): String {
        val user = android.provider.Settings.Global.getString(
            appContext?.contentResolver, "device_name",
        )
        return (user ?: android.os.Build.MODEL ?: "phone").take(40)
    }

    // --- alerts --------------------------------------------------------------
    private val alertEngine = AlertEngine()

    /** Evaluate a reading against the configured rules and carry out any that fire. */
    private fun checkAlerts(values: Map<String, Double>) {
        val ctx = appContext ?: return
        val rules = _ui.value.config.alerts
        val fired = alertEngine.evaluate(rules, values)
        for (rule in fired) {
            AlertNotifier.fire(ctx, rule, values[rule.field] ?: rule.threshold)
        }
        val active = alertEngine.active
        if (active != _ui.value.activeAlerts) _ui.update { it.copy(activeAlerts = active) }
    }

    /**
     * Fire [rule] as if the air had just crossed it.
     *
     * Waiting for genuinely bad air to find out whether alerts are wired up is
     * not a reasonable test loop, so this exercises the real reaction path -
     * same notifier, same channels - and only the wording differs.
     */
    fun testAlert(rule: AlertRule) {
        val ctx = appContext ?: return
        alertEngine.rearm(rule)
        AlertNotifier.fire(ctx, rule, rule.threshold + 1.0, simulated = true)
    }

    // --- gesture coalescing -------------------------------------------------
    // Pointer events arrive at display rate (~120Hz on the S24). Applying and
    // recomputing per event queued work faster than the model thread drained it,
    // so the chart kept tracking stale deltas after the finger lifted - the
    // "momentum" effect. Instead deltas accumulate and exactly one apply+recompute
    // is ever in flight; anything that arrives meanwhile lands in the next flush.
    private val gestureLock = Any()
    private var pendingPan = 0.0
    private var pendingZoom = 1.0
    private var flushInFlight = false
    @Volatile private var gestureActive = false

    /** Finger down on the chart: the 1Hz ticker stands aside until [endGesture]. */
    fun beginGesture() { gestureActive = true }

    /** Finger up: drain any residual delta, then commit a manually dragged pin. */
    fun endGesture() {
        gestureActive = false
        scope.launch {
            applyPending()
            chart.commitManualPin()
            recompute()
        }
    }

    fun zoomBy(factor: Double) {
        synchronized(gestureLock) { pendingZoom *= factor }
        scheduleFlush()
    }

    fun panBy(deltaSeconds: Double) {
        synchronized(gestureLock) { pendingPan += deltaSeconds }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        synchronized(gestureLock) {
            if (flushInFlight) return
            flushInFlight = true
        }
        scope.launch {
            try {
                applyPending()
                recompute()
            } finally {
                val more = synchronized(gestureLock) {
                    flushInFlight = false
                    pendingPan != 0.0 || pendingZoom != 1.0
                }
                if (more) scheduleFlush()
            }
        }
    }

    /** Apply and clear the accumulated deltas. Runs on the model dispatcher. */
    private fun applyPending() {
        val pan: Double
        val zoom: Double
        synchronized(gestureLock) {
            pan = pendingPan
            zoom = pendingZoom
            pendingPan = 0.0
            pendingZoom = 1.0
        }
        if (zoom == 1.0 && pan == 0.0) return
        val now = currentNow()
        if (zoom != 1.0) chart.zoom(zoom, now)
        if (pan != 0.0) chart.pan(pan, now)
    }

    // --- commands (routed through the active transport) ---------------------
    private fun sendCommand(line: String) { transport?.send(line) }
    fun pause() = sendCommand(Commands.pause())
    fun resume() = sendCommand(Commands.resume())
    fun clean() = sendCommand(Commands.clean())
    fun shutdownSensorHost() = sendCommand(Commands.shutdown())

    /** Switch the daemon's acquisition profile (continuous vs low-power). */
    fun setProfile(
        mode: String,
        intervalSeconds: Double,
        cycleSeconds: Double,
        warmupSeconds: Double,
        averageSeconds: Double,
    ) = sendCommand(
        Commands.setProfile(mode, intervalSeconds, cycleSeconds, warmupSeconds, averageSeconds)
    )

    /** Restart begins a *new session*: the sensor is restarted AND the plot is
     *  cleared (a fresh session start for "all"). Pause+Resume keeps the plot. */
    fun restart() = onModel {
        sendCommand(Commands.restart())
        history.clear()
    }

    // --- chart interaction (confined to the model dispatcher) ---------------

    /** A window-size button. "all" (>= [AppConfig.ALL_SECONDS]) shows the whole
     *  current session from its first stable reading; others are fixed windows. */
    fun onWindow(seconds: Double) = onModel {
        if (seconds >= AppConfig.ALL_SECONDS) {
            // "All" means everything on the plot, stored sessions included. Auto
            // Set stays with the live session; the two questions differ once an
            // archive is loaded, so they get different answers.
            chart.applyAll(history.firstStableEpoch() ?: currentNow(), currentNow())
        } else {
            chart.applyWindow(seconds, currentNow())
        }
    }
    /**
     * The Pin button is a single toggle: engaged pins the view, disengaged is
     * live. Two buttons for one either/or state cost main-screen width and made
     * "which one am I in" a thing to work out rather than see.
     */
    fun onPinTap() = onModel {
        if (chart.viewMode() == ViewMode.PIN) chart.goLive(currentNow())
        else chart.pinTap(currentNow())
    }
    fun onPinLongPress() = onModel { chart.pinLongPress(currentNow()) }
    fun goLive() = onModel { chart.goLive(currentNow()) }
    fun clearPlot() = onModel { history.clear() }

    /** An explicit averaging interval from the main view; null/1s means raw. */
    fun setPlotInterval(seconds: Double?) = onModel { chart.setPlotInterval(seconds) }

    /**
     * Auto Set: frame the whole session and pick its own averaging.
     *
     * This is the "show me what I have" button - it fits x to every reading in
     * the session (what the old "all" button did) and then lets the chart choose
     * an averaging bucket from the visible span, so a multi-day session arrives
     * as a readable curve rather than a solid band. Zooming in afterwards walks
     * the averaging back down on its own.
     */
    fun autoSet() = onModel {
        chart.setAutoInterval()
        chart.applyAll(history.firstStableLiveEpoch() ?: currentNow(), currentNow())
    }
    fun setPinCycleCount(n: Int) = onModel { chart.setPinCycleCount(n) }

    // --- stored sessions ----------------------------------------------------

    private var importJob: Job? = null

    /**
     * Read every session stored on this device into the plot.
     *
     * Runs on the model dispatcher rather than an IO one, because it mutates the
     * same history the live stream writes to and that store is single-threaded
     * by design. The cost of that choice is paid back by suspending between
     * sessions: the message collector and the 1 Hz recompute interleave there,
     * so a minute-long read does not stall a live connection.
     */
    fun loadStoredSessions() {
        val context = appContext ?: return
        if (importJob?.isActive == true) return
        importJob = scope.launch {
            _ui.update { it.copy(storedImport = StoredImport.Scanning) }
            try {
                val sources = withContext(Dispatchers.IO) { StoredSessions.list(context) }
                if (sources.isEmpty()) {
                    _ui.update {
                        it.copy(
                            storedImport = StoredImport.Failed(
                                "No stored sessions found in Downloads/SPS30. " +
                                    "Copy them from the sensor first."
                            )
                        )
                    }
                    return@launch
                }
                val bytes = withContext(Dispatchers.IO) {
                    StoredSessions.estimateBytes(context, sources)
                }
                val bucket = StoredImport.bucketFor(bytes)
                val tally = StoredSessions.importInto(
                    context, sources, history, bucket, StoredImport.MAX_POINTS,
                ) { name, done, total, points ->
                    _ui.update { it.copy(storedImport = StoredImport.Loading(name, done, total, points)) }
                    // The whole point of suspending here: let anything else on
                    // this single-threaded dispatcher have a turn.
                    yield()
                }
                _ui.update {
                    it.copy(
                        storedImport = StoredImport.Loaded(
                            sessions = tally.sessions,
                            points = tally.points,
                            rows = tally.rows,
                            empty = tally.empty,
                            unrecognised = tally.unrecognised,
                            bucketSeconds = bucket,
                            start = history.earliestEpoch() ?: 0.0,
                            end = history.latestEpoch() ?: 0.0,
                            truncated = tally.truncated,
                        )
                    )
                }
                // Frame what just arrived, so the answer to "what is in those
                // files" is on screen without a second gesture.
                chart.setAutoInterval()
                chart.applyAll(history.firstStableEpoch() ?: currentNow(), currentNow())
                recompute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(storedImport = StoredImport.Failed(e.message ?: "could not read stored sessions")) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { StoredSessions.releaseCache(context) }
            }
        }
    }

    /** Drop the imported sessions again, leaving the live stream alone. */
    fun clearStoredSessions() {
        importJob?.cancel()
        onModel {
            history.clearImported()
            _ui.update { it.copy(storedImport = StoredImport.Idle) }
            chart.goLive(currentNow())
        }
    }

    /** CSV of every buffered point (Eastern timestamps), read on the model thread. */
    suspend fun buildCsv(): String = withContext(confinement) {
        buildString {
            append("timestamp,index,").append(Wire.PM_FIELDS.joinToString(","))
                .append(',').append(Wire.COL_EVENT).append('\n')
            for (s in history.snapshot()) {
                val ts = Instant.ofEpochSecond(s.epoch.toLong())
                    .atZone(TimeSeriesHistory.EASTERN).format(CSV_TIME)
                append(ts).append(',').append(s.index)
                for (f in Wire.PM_FIELDS) append(',').append(s.values[f] ?: 0.0)
                append(',').append(s.event).append('\n')
            }
        }
    }

    /** Apply a full config: pushes pin-cycle to the chart, publishes it for the UI,
     *  and (re)starts the connection when the connection mode changes. */
    fun applyConfig(config: AppConfig) = onModel {
        chart.setPinCycleCount(config.pinCycleCount)
        val modeChanged = config.connectionMode != currentMode
        _ui.update { it.copy(config = config) }
        if (modeChanged) startConnection(config.connectionMode)
    }

    fun setYAutoscale(on: Boolean) = onModel {
        if (on) chart.yAutoscale(true)
        else chart.yAutoscale(false, _ui.value.chart?.dataYMin, _ui.value.chart?.dataYMax)
    }

    fun setYRange(min: Double, max: Double) = onModel { chart.setYRange(min, max) }

    private inline fun onModel(crossinline block: () -> Unit) {
        scope.launch {
            block()
            recompute()
        }
    }
}
