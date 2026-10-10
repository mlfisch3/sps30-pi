package com.sensirion.sps30.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sensirion.sps30.config.ConfigStore
import com.sensirion.sps30.config.Exports
import com.sensirion.sps30.data.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Thin bridge to the process-lifetime [MonitorSession], plus config persistence
 * (via [ConfigStore], which needs a Context - hence AndroidViewModel). The
 * session owns the connection so streaming survives Activity recreation.
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    init {
        MonitorSession.ensureStarted(app)
        MonitorSession.applyConfig(ConfigStore.loadCurrent(app))
    }

    val ui: StateFlow<DashboardUiState> = MonitorSession.ui

    // --- daemon commands ---
    fun pause() = MonitorSession.pause()
    fun resume() = MonitorSession.resume()
    fun clean() = MonitorSession.clean()
    fun restart() = MonitorSession.restart()
    fun shutdownSensorHost() = MonitorSession.shutdownSensorHost()
    fun setProfile(
        mode: String,
        intervalSeconds: Double,
        cycleSeconds: Double,
        warmupSeconds: Double,
        averageSeconds: Double,
    ) = MonitorSession.setProfile(
        mode, intervalSeconds, cycleSeconds, warmupSeconds, averageSeconds,
    )

    // --- chart interaction ---
    fun onWindow(seconds: Double) = MonitorSession.onWindow(seconds)
    fun onPinTap() = MonitorSession.onPinTap()
    fun onPinLongPress() = MonitorSession.onPinLongPress()
    fun beginGesture() = MonitorSession.beginGesture()
    fun endGesture() = MonitorSession.endGesture()
    fun goLive() = MonitorSession.goLive()
    fun zoomBy(factor: Double) = MonitorSession.zoomBy(factor)
    fun panBy(deltaSeconds: Double) = MonitorSession.panBy(deltaSeconds)
    fun setChartWidthPx(width: Int) = MonitorSession.setChartWidthPx(width)
    fun clearPlot() = MonitorSession.clearPlot()
    fun setPlotInterval(seconds: Double?) = MonitorSession.setPlotInterval(seconds)
    fun autoSet() = MonitorSession.autoSet()
    /** Show or hide a PM series on the plot, from its readout button. */
    fun toggleSeries(field: String) = updateConfig(ui.value.config.toggleVisible(field))

    fun setReadoutStyle(style: com.sensirion.sps30.data.ReadoutStyle) =
        updateConfig(ui.value.config.copy(readoutStyle = style))

    fun setLogY(on: Boolean) = updateConfig(ui.value.config.copy(logY = on))

    // --- alerts ---
    fun updateAlert(rule: com.sensirion.sps30.data.AlertRule) {
        val next = ui.value.config.alerts.map { if (it.id == rule.id) rule else it }
        updateConfig(ui.value.config.copy(alerts = next))
    }

    fun testAlert(rule: com.sensirion.sps30.data.AlertRule) = MonitorSession.testAlert(rule)

    // --- multi-user control ---
    fun copySessionsToThisDevice() = MonitorSession.copySessionsToThisDevice()
    fun dismissBackupResult() = MonitorSession.dismissBackupResult()

    fun claimControl() = MonitorSession.claimControl()
    fun releaseControl() = MonitorSession.releaseControl()
    fun dismissControlDenied() = MonitorSession.dismissControlDenied()
    fun setYAutoscale(on: Boolean) = MonitorSession.setYAutoscale(on)
    fun setYRange(min: Double, max: Double) = MonitorSession.setYRange(min, max)

    // --- config: persist current, apply live, and manage named saves ---
    fun updateConfig(config: AppConfig) {
        ConfigStore.saveCurrent(getApplication(), config)
        MonitorSession.applyConfig(config)
    }

    fun resetConfigToDefault() = updateConfig(AppConfig.default())

    fun setBackgroundMonitoring(on: Boolean) =
        updateConfig(ui.value.config.copy(backgroundMonitoring = on))

    /** Toggle excluding disturbed settling/clean-spike readings from the plot. */
    fun setExcludeFlagged(on: Boolean) = updateConfig(ui.value.config.copy(excludeFlagged = on))

    /** Switch transport (Wi-Fi <-> Bluetooth). Persisted; MonitorSession reconnects. */
    fun setConnectionMode(mode: com.sensirion.sps30.data.ConnectionMode) =
        updateConfig(ui.value.config.copy(connectionMode = mode))

    fun saveConfigAs(name: String) =
        ConfigStore.saveNamed(getApplication(), name, ui.value.config)

    fun savedConfigNames(): List<String> = ConfigStore.savedNames(getApplication())

    fun loadSavedConfig(name: String) {
        ConfigStore.loadNamed(getApplication(), name)?.let { updateConfig(it) }
    }

    fun deleteSavedConfig(name: String) = ConfigStore.deleteNamed(getApplication(), name)

    // --- stored sessions ---
    fun loadStoredSessions() = MonitorSession.loadStoredSessions()
    fun clearStoredSessions() = MonitorSession.clearStoredSessions()

    // --- export ---
    fun exportCsv(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val csv = MonitorSession.buildCsv()
            val location = withContext(Dispatchers.IO) { Exports.writeCsv(getApplication(), csv) }
            onResult(location)
        }
    }

    fun savePng(bitmap: Bitmap, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val location = withContext(Dispatchers.IO) { Exports.writePng(getApplication(), bitmap) }
            onResult(location)
        }
    }
}
