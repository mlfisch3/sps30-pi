package com.sensirion.sps30.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sensirion.sps30.MainActivity
import com.sensirion.sps30.R
import com.sensirion.sps30.net.ConnectionState
import com.sensirion.sps30.ui.DashboardUiState

/** Builds and channels the ongoing foreground-monitoring notification. */
object Notifications {
    const val ID = 42
    private const val CHANNEL = "sps30_monitor"

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(
                CHANNEL,
                "SPS30 monitoring",
                NotificationManager.IMPORTANCE_LOW, // ongoing, silent
            ).apply {
                description = "Keeps the live particulate-matter stream running in the background."
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    fun build(context: Context, state: DashboardUiState): Notification {
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, Sps30Service::class.java).setAction(Sps30Service.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sps30)
            .setContentTitle(title(state))
            .setContentText(readout(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun title(state: DashboardUiState): String {
        if (state.discovering) return "SPS30 · searching for daemon…"
        if (state.discoveryFailed) return "SPS30 · no daemon found"
        return when (val c = state.connection) {
            is ConnectionState.Connected ->
                "SPS30 · ${state.daemonState ?: "connected"} · ${c.endpoint}"
            is ConnectionState.Connecting -> "SPS30 · connecting…"
            is ConnectionState.Reconnecting -> "SPS30 · reconnecting…"
            ConnectionState.Idle -> "SPS30 monitoring"
        }
    }

    private fun readout(state: DashboardUiState): String {
        val pm25 = state.latest["pm2_5"]
        val pm10 = state.latest["pm10"]
        if (pm25 == null && pm10 == null) return "Waiting for readings…"
        return "PM2.5 %.1f · PM10 %.1f µg/m³".format(pm25 ?: 0.0, pm10 ?: 0.0)
    }
}
