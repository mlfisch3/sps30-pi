package com.sensirion.sps30.wear.relay

import android.content.ComponentName
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.sensirion.sps30.wear.WearContract
import com.sensirion.sps30.wear.complication.Pm25ComplicationService
import com.sensirion.sps30.wear.tile.Pm25TileService

/**
 * Receives the phone's relayed [WearContract.PATH_LATEST] DataItem, caches it, and
 * asks the system to refresh the PM2.5 complication. This is what makes the
 * complication update "live" between its scheduled periodic refreshes: each new
 * reading the phone publishes drives one complication update here.
 */
class DataLayerListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        var valueChanged = false
        var received = false
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != WearContract.PATH_LATEST) continue

            val map = DataMapItem.fromDataItem(item).dataMap
            val reading = LatestReading(
                pm1_0 = map.getDouble(WearContract.KEY_PM1_0, Double.NaN),
                pm2_5 = map.getDouble(WearContract.KEY_PM2_5, Double.NaN),
                pm4_0 = map.getDouble(WearContract.KEY_PM4_0, Double.NaN),
                pm10 = map.getDouble(WearContract.KEY_PM10, Double.NaN),
                timestamp = map.getString(WearContract.KEY_TIMESTAMP),
                sentAt = map.getLong(WearContract.KEY_SENT_AT, System.currentTimeMillis()),
                spark = map.getFloatArray(WearContract.KEY_SPARK_PM25) ?: FloatArray(0),
            )
            val previous = LatestReadingStore.load(this)
            LatestReadingStore.save(this, reading)
            received = true
            if (previous == null || previous.pm2_5 != reading.pm2_5) valueChanged = true
        }

        // The complication shows PM2.5, so only redraw it when that number moved -
        // a heartbeat push that merely refreshes freshness should not cost a redraw.
        if (valueChanged) {
            ComplicationDataSourceUpdateRequester
                .create(this, ComponentName(this, Pm25ComplicationService::class.java))
                .requestUpdateAll()
        }
        // The Tile also draws a sparkline, which moves whenever time does, but it is
        // only worth re-rendering when the tile is actually on screen - its own
        // freshness interval covers that case, so this is a nudge, not a schedule.
        if (received) {
            TileService.getUpdater(this).requestUpdate(Pm25TileService::class.java)
        }
    }
}
