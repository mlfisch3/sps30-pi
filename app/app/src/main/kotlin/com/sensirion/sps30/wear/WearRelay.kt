package com.sensirion.sps30.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Phone-side publisher: pushes the latest [com.sensirion.sps30.wire.ServerMessage.Reading]
 * to the Wearable Data Layer so a paired Galaxy Watch 7 complication can glance at
 * the current PM2.5 without its own daemon connection.
 *
 * Battery-conscious by design:
 *  - It writes a single, overwritten DataItem at [WearContract.PATH_LATEST] (not a
 *    stream of messages), so only the most recent value is ever synced.
 *  - Readings arrive at ~1 Hz but [com.sensirion.sps30.ui.MonitorSession] only calls
 *    this when the reading actually moved, or on a slow heartbeat.
 *  - [publishedAtMs] is quantised (see [WearContract.SENT_AT_BUCKET_MS]) so an
 *    unchanged reading yields a byte-identical DataItem, which the Data Layer then
 *    drops for free rather than syncing it to the watch.
 *  - `setUrgent()` is deliberately NOT used: the OS may batch the sync to save
 *    radio power, which is the right trade-off for an ambient air-quality glance.
 */
object WearRelay {
    private const val TAG = "WearRelay"

    /** Publish the latest reading plus a [spark] PM2.5 series to the Data Layer.
     *  The caller ([com.sensirion.sps30.ui.MonitorSession]) already decides *whether*
     *  a push is warranted, so there is no throttle here. */
    fun publish(
        context: Context,
        values: Map<String, Double>,
        timestamp: String?,
        spark: FloatArray,
        publishedAtMs: Long = System.currentTimeMillis(),
        relaySeconds: Int = 0,
    ) {
        val now = publishedAtMs / WearContract.SENT_AT_BUCKET_MS * WearContract.SENT_AT_BUCKET_MS
        val request = PutDataMapRequest.create(WearContract.PATH_LATEST).apply {
            dataMap.putDouble(WearContract.KEY_PM1_0, values["pm1_0"] ?: Double.NaN)
            dataMap.putDouble(WearContract.KEY_PM2_5, values["pm2_5"] ?: Double.NaN)
            dataMap.putDouble(WearContract.KEY_PM4_0, values["pm4_0"] ?: Double.NaN)
            dataMap.putDouble(WearContract.KEY_PM10, values["pm10"] ?: Double.NaN)
            timestamp?.let { dataMap.putString(WearContract.KEY_TIMESTAMP, it) }
            dataMap.putFloatArray(WearContract.KEY_SPARK_PM25, spark)
            dataMap.putLong(WearContract.KEY_SENT_AT, now)
            dataMap.putInt(WearContract.KEY_RELAY_SECONDS, relaySeconds)
        }.asPutDataRequest()

        Wearable.getDataClient(context).putDataItem(request)
            .addOnFailureListener { e -> Log.w(TAG, "Data Layer publish failed", e) }
    }
}
