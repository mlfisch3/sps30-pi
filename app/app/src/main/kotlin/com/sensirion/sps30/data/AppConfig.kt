package com.sensirion.sps30.data

import com.sensirion.sps30.wire.Wire
import kotlinx.serialization.Serializable

/** A labelled window-size button (e.g. "5m" -> 300s; "all" -> the retention span). */
@Serializable
data class WindowPreset(val label: String, val seconds: Double)

/**
 * How the app reaches the sensor host: Wi-Fi/TCP, Bluetooth LE (no network needed),
 * or [NONE] - deliberately not connected.
 *
 * [NONE] is a phone-side state only. The sensor host owns the serial port and writes
 * its CSV regardless of who is watching, so disconnecting (or the phone's battery
 * dying) never interrupts recording; reconnecting backfills the gap. It exists so
 * stepping away is a deliberate action rather than force-quitting the app.
 */
enum class ConnectionMode { WIFI, BLUETOOTH, NONE }

/**
 * How the PM readouts are laid out.
 *
 * [STACKED] is the compact original - label, reading and unit on three centred
 * lines - and is the default because the number carries best when nothing shares
 * its line. [INLINE] puts all three on one line, which fills a wide tile
 * handsomely but asks the eye to take in more at once and leaves the number
 * competing for attention.
 */
@Serializable
enum class ReadoutStyle { STACKED, INLINE }

/** User-customizable settings, persisted and editable from the options menu. */
@Serializable
data class AppConfig(
    /** How many recent pin origins the Pin button cycles through. */
    val pinCycleCount: Int = ChartView.DEFAULT_PIN_CYCLE,
    /** Which PM series are drawn on the chart (readouts always show all four). */
    val visiblePm: List<String> = Wire.PM_FIELDS,
    /** The window-size buttons, in order. */
    val windowPresets: List<WindowPreset> = DEFAULT_PRESETS,
    /** Wi-Fi/TCP (default) or Bluetooth LE. */
    val connectionMode: ConnectionMode = ConnectionMode.WIFI,
    /** Exclude disturbed settling/clean-spike readings from the plot (on by default). */
    val excludeFlagged: Boolean = true,
    /** Minimum gap between pushes to the watch. Each push wakes the watch's radio,
     *  rewrites its cache and forces a complication redraw, so this is the main
     *  lever on watch battery drain. */
    val watchRelaySeconds: Int = DEFAULT_WATCH_RELAY_SECONDS,
    /** Keep streaming when the app is not in front. On by default: the sensor
     *  records regardless, but without this the phone's own history develops
     *  gaps the moment the screen sleeps, which reads as data loss. */
    val backgroundMonitoring: Boolean = true,
    /** Averaging buttons offered on the main plot view, in seconds. 1 = raw. */
    val plotIntervals: List<Double> = DEFAULT_PLOT_INTERVALS,
    /** Plot PM on a log y-axis, so a spike two decades up does not flatten the rest. */
    val logY: Boolean = false,
    /** Layout of the two PM readout buttons on the main view. */
    val readoutStyle: ReadoutStyle = ReadoutStyle.STACKED,
    /** Threshold rules. Phone-side only; never sent to the controller. */
    val alerts: List<AlertRule> = AlertEngine.defaults(),
) {
    fun withPinCycleCount(n: Int) = copy(pinCycleCount = n.coerceIn(1, 20))

    fun toggleVisible(field: String): AppConfig {
        val next = if (field in visiblePm) visiblePm - field else visiblePm + field
        // Keep canonical order and never let the plot go fully empty.
        val ordered = Wire.PM_FIELDS.filter { it in next }
        return copy(visiblePm = if (ordered.isEmpty()) visiblePm else ordered)
    }

    companion object {
        /**
         * Recording intervals offered for CONTINUOUS mode, in seconds.
         *
         * These change how much DATA is recorded, not how much power is used -
         * the fan and laser stay on regardless, so the sensor still draws ~55 mA.
         * Past about a minute the honest answer is low-power mode instead, which
         * is what [SUGGEST_DUTY_CYCLE_ABOVE] drives.
         */
        val CONTINUOUS_INTERVALS = listOf(1.0, 10.0, 30.0, 60.0)

        /** Above this continuous interval, prompt the user towards duty cycling. */
        const val SUGGEST_DUTY_CYCLE_ABOVE = 60.0

        /**
         * Low-power cycle periods, in seconds. 15 min is Sensirion's own
         * recommendation - their field data shows it catches events like cooking
         * while cutting average current from ~55 mA to ~3.5 mA.
         *
         * 12 h and 24 h go past anything Sensirion discusses, deliberately: for a
         * gadget in storage or left behind while away, the useful question is no
         * longer "will it catch a cooking event" but "will there be any charge
         * left in a month", and at that duty cycle self-discharge dominates
         * measurement entirely.
         */
        val DUTY_CYCLE_PERIODS = listOf(300.0, 900.0, 1800.0, 3600.0, 43_200.0, 86_400.0)

        /** "all" maps to this many seconds - the daemon/store retention horizon. */
        const val ALL_SECONDS = 172_800.0 // 48h

        /** 3 min: readings arrive ~1Hz but an ambient air-quality glance does not
         *  need that, and the complication's own refresh period is 5 min anyway. */
        const val DEFAULT_WATCH_RELAY_SECONDS = 180

        /** Offered in the options sheet, in seconds. */
        val WATCH_RELAY_CHOICES = listOf(60, 180, 300, 900)

        val DEFAULT_PRESETS: List<WindowPreset> = listOf(
            WindowPreset("1m", 60.0),
            WindowPreset("5m", 300.0),
            WindowPreset("30m", 1800.0),
            WindowPreset("1h", 3600.0),
        )

        /** 1s means raw samples; the rest average over that many seconds. */
        val DEFAULT_PLOT_INTERVALS = listOf(1.0, 5.0, 10.0, 30.0, 60.0, 3600.0)

        fun default() = AppConfig()

        /** Parse "1s,5s,30s,1m,1h" into plot intervals; bad tokens are dropped. */
        fun parseIntervals(text: String): List<Double> {
            val out = text.split(",").mapNotNull { token ->
                val t = token.trim()
                if (t.isEmpty()) null else runCatching { Durations.parse(t) }.getOrNull()
            }
            return out.ifEmpty { DEFAULT_PLOT_INTERVALS }
        }

        /** "1s", "30s", "5m", "1h" - matches how the buttons are labelled. */
        fun intervalLabel(seconds: Double): String = when {
            seconds < 60 -> "${seconds.toInt()}s"
            seconds < 3600 -> "${(seconds / 60).toInt()}m"
            else -> "${(seconds / 3600).toInt()}h"
        }

        fun intervalsToText(values: List<Double>): String =
            values.joinToString(", ") { intervalLabel(it) }

        /** Parse "10s,30s,1m,10m,1h,5h,all" into presets; bad tokens are dropped. */
        fun parsePresets(text: String): List<WindowPreset> {
            val presets = text.split(",").mapNotNull { token ->
                val t = token.trim()
                when {
                    t.isEmpty() -> null
                    t.equals("all", ignoreCase = true) -> WindowPreset("all", ALL_SECONDS)
                    else -> runCatching { WindowPreset(t, Durations.parse(t)) }.getOrNull()
                }
            }
            return presets.ifEmpty { DEFAULT_PRESETS }
        }

        fun presetsToText(presets: List<WindowPreset>): String =
            presets.joinToString(", ") { it.label }
    }
}
