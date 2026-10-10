package com.sensirion.sps30.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sensirion.sps30.ui.MonitorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps [MonitorSession] streaming while the app is
 * backgrounded, surfacing status + the latest PM readout in an ongoing
 * notification. Declared as a `dataSync` foreground service.
 */
class Sps30Service : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to the foreground FIRST, before any other work.
        //
        // startForegroundService() gives roughly five seconds to reach
        // startForeground(), and missing it does not fail quietly - the system
        // kills the whole process with ForegroundServiceDidNotStartInTime. Work
        // that ran ahead of this call (starting the session, which now also
        // registers a network request) was enough to lose that race, and because
        // background monitoring is on by default the app crash-looped on launch.
        // Nothing goes above this line.
        Notifications.ensureChannel(this)
        if (!startForegroundCompat()) {
            // The system refused to let us run in the foreground - most likely
            // started from the background under Android 12+ restrictions. Give up
            // quietly rather than leaving a half-started service around.
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        MonitorSession.ensureStarted(applicationContext)
        MonitorSession.setBackgroundEnabled(true)
        acquireWifiLock()

        if (!running) {
            running = true
            scope.launch {
                MonitorSession.ui.collect { state ->
                    if (running) {
                        NotificationManagerCompat.from(this@Sps30Service)
                            .notify(Notifications.ID, Notifications.build(this@Sps30Service, state))
                    }
                }
            }
        }
        return START_STICKY
    }

    /** Android 15+ stops `dataSync` FGS after its daily budget; exit cleanly. */
    override fun onTimeout(startId: Int) {
        stopMonitoring()
    }

    override fun onDestroy() {
        releaseWifiLock()
        scope.cancel()
        super.onDestroy()
    }

    /** Promote to the foreground. False if the system refused. */
    private fun startForegroundCompat(): Boolean = try {
        val notification = Notifications.build(this, MonitorSession.ui.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifications.ID, notification)
        }
        true
    } catch (e: Exception) {
        // Android 12+ can refuse a background start outright. Streaming while
        // the app is open still works; only the backgrounded case is lost, and
        // that is a far better outcome than taking the app down with it.
        android.util.Log.w("Sps30Service", "could not enter the foreground", e)
        false
    }

    /**
     * Hold the Wi-Fi radio awake while monitoring. A foreground service keeps
     * the process alive but does NOT stop the Wi-Fi radio from powering down in
     * doze - observed on the S24, where Wi-Fi drops off the LAN once the screen
     * sleeps. This keeps the stream reachable in the background. (For 24/7 use
     * the app should also be exempt from battery optimisation - see README.)
     */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sps30:monitor").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun stopMonitoring() {
        running = false
        releaseWifiLock()
        MonitorSession.setBackgroundEnabled(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.sensirion.sps30.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, Sps30Service::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, Sps30Service::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
