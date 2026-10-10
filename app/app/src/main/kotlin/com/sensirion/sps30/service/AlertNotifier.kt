package com.sensirion.sps30.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sensirion.sps30.MainActivity
import com.sensirion.sps30.data.AlertRule

/**
 * Carries out an alert rule's reactions.
 *
 * Separate from [AlertEngine] (which only decides *whether* to fire) so the
 * decision logic stays pure and testable while everything platform-shaped lives
 * here. Each reaction is independent and failure of one must not suppress the
 * others - a denied notification permission should still let the phone vibrate.
 */
object AlertNotifier {

    private const val CHANNEL_ID = "sps30_alerts"
    private const val CHANNEL_ID_URGENT = "sps30_alerts_urgent"

    /** Notification ids are per-rule so levels replace themselves, not each other. */
    private fun notificationId(rule: AlertRule): Int = 4000 + rule.id.hashCode().and(0xFFF)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Air quality alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Fires when a PM threshold you set is crossed."
                setSound(null, null) // the rule decides; a silent channel is the default
                enableVibration(false)
            },
        )
        // A second channel because sound and vibration are channel properties on
        // O+, not per-notification, so rules that want them need their own.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_URGENT,
                "Air quality alerts (sound and vibration)",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Higher thresholds that should interrupt you."
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build(),
                )
            },
        )
    }

    /**
     * React to [rule] having fired at [value]. [simulated] marks a test breach so
     * the notification says so and cannot be mistaken for real air.
     */
    fun fire(context: Context, rule: AlertRule, value: Double, simulated: Boolean = false) {
        ensureChannels(context)
        if (rule.notify) postNotification(context, rule, value, simulated)
        // Vibration is requested explicitly as well as via the channel, because a
        // notification the user has silenced in system settings still should not
        // swallow a vibration they asked this rule for.
        if (rule.vibrate) vibrate(context)
    }

    private fun postNotification(
        context: Context,
        rule: AlertRule,
        value: Double,
        simulated: Boolean,
    ) {
        val channel = if (rule.sound || rule.vibrate) CHANNEL_ID_URGENT else CHANNEL_ID
        val title = buildString {
            if (simulated) append("Test: ")
            append(rule.label)
            append(" air quality")
        }
        val body = "${Fields.label(rule.field)} ${"%.1f".format(value)} ug/m3 " +
            "(threshold ${"%.0f".format(rule.threshold)})"

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    context,
                    0,
                    android.content.Intent(context, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(
                if (rule.sound || rule.vibrate) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId(rule), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied. The other reactions still ran.
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return
        // Two firm pulses: distinguishable from an ordinary notification buzz
        // without being an alarm.
        val pattern = longArrayOf(0, 220, 140, 220)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator.vibrate(pattern, -1)
        }
    }

    private object Fields {
        fun label(field: String): String = when (field) {
            "pm1_0" -> "PM1.0"
            "pm2_5" -> "PM2.5"
            "pm4_0" -> "PM4.0"
            "pm10" -> "PM10"
            else -> field
        }
    }
}
