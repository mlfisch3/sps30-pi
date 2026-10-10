package com.sensirion.sps30.data

/**
 * Progress of copying every recorded session from the sensor to this device.
 *
 * Named for what it actually does. "Export" had come to mean two different
 * things - writing the plotted data out as a CSV on this device, and pulling the
 * sensor's whole archive across the network - and the two are worth keeping
 * verbally distinct, because confusing them risks someone believing they have a
 * backup when they have a screenshot's worth of numbers.
 */
sealed interface SessionBackup {
    data object Idle : SessionBackup

    /** Asked, but the sensor has not yet said how much is coming. */
    data object Requested : SessionBackup

    data class Receiving(
        val received: Long,
        val total: Long,
        val sessions: Int,
        val rows: Long,
    ) : SessionBackup {
        val fraction: Float
            get() = if (total > 0) (received.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }

    /** Saved. [location] is a human-readable path to show the user. */
    data class Saved(
        val location: String,
        val bytes: Long,
        val sessions: Int,
        val rows: Long,
    ) : SessionBackup

    data class Failed(val reason: String) : SessionBackup
}
