package com.sensirion.sps30.wear.relay

import android.content.Context

/** The most recent reading relayed from the phone, plus when it was sent. */
data class LatestReading(
    val pm1_0: Double,
    val pm2_5: Double,
    val pm4_0: Double,
    val pm10: Double,
    val timestamp: String?,
    val sentAt: Long,
    /** The phone's relay interval in seconds, 0 when it did not say. */
    val relaySeconds: Int = 0,
    /** Last-hour PM2.5 series (oldest -> newest) for the Tile sparkline. */
    val spark: FloatArray = FloatArray(0),
)

/**
 * Tiny durable cache of the last relayed reading, backed by SharedPreferences.
 *
 * The complication data source reads this synchronously whenever the system asks
 * for data; the Data Layer listener writes it whenever the phone relays a new
 * value. Persisting it means the complication still shows the last-known value
 * after a watch reboot (marked stale by age).
 */
object LatestReadingStore {
    private const val PREFS = "sps30_latest"
    private const val K_PM1_0 = "pm1_0"
    private const val K_PM2_5 = "pm2_5"
    private const val K_PM4_0 = "pm4_0"
    private const val K_PM10 = "pm10"
    private const val K_TS = "timestamp"
    private const val K_SENT_AT = "sent_at"
    private const val K_SPARK = "spark"
    private const val K_RELAY = "relay_seconds"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, r: LatestReading) {
        prefs(context).edit().apply {
            putLong(K_PM1_0, r.pm1_0.toRawBits())
            putLong(K_PM2_5, r.pm2_5.toRawBits())
            putLong(K_PM4_0, r.pm4_0.toRawBits())
            putLong(K_PM10, r.pm10.toRawBits())
            if (r.timestamp != null) putString(K_TS, r.timestamp) else remove(K_TS)
            putString(K_SPARK, r.spark.joinToString(",") { it.toString() })
            putLong(K_SENT_AT, r.sentAt)
            putInt(K_RELAY, r.relaySeconds)
        }.apply()
    }

    /** Returns the cached reading, or null if the phone has never relayed one. */
    fun load(context: Context): LatestReading? {
        val p = prefs(context)
        if (!p.contains(K_SENT_AT)) return null
        fun d(k: String) = Double.fromBits(p.getLong(k, 0L))
        val sparkStr = p.getString(K_SPARK, "") ?: ""
        val spark = if (sparkStr.isEmpty()) FloatArray(0)
            else sparkStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        return LatestReading(
            pm1_0 = d(K_PM1_0),
            pm2_5 = d(K_PM2_5),
            pm4_0 = d(K_PM4_0),
            pm10 = d(K_PM10),
            timestamp = p.getString(K_TS, null),
            sentAt = p.getLong(K_SENT_AT, 0L),
            relaySeconds = p.getInt(K_RELAY, 0),
            spark = spark,
        )
    }
}
