package com.sensirion.sps30.wear

/**
 * The Wearable Data Layer contract for the phone -> watch relay of the latest
 * SPS30 reading. This MUST stay a byte-for-byte match of the copy in the phone
 * `:app` module (path + key names); the two are separate APKs so the object is
 * duplicated rather than shared.
 */
object WearContract {
    /** DataItem path holding the single most-recent reading. */
    const val PATH_LATEST = "/sps30/latest"

    const val KEY_PM1_0 = "pm1_0"
    const val KEY_PM2_5 = "pm2_5"
    const val KEY_PM4_0 = "pm4_0"
    const val KEY_PM10 = "pm10"

    /** ISO-8601 timestamp string of the reading (from the daemon). */
    const val KEY_TIMESTAMP = "timestamp"

    /** Epoch millis when the phone published this item, quantised to
     *  [SENT_AT_BUCKET_MS]. Used to judge staleness. */
    const val KEY_SENT_AT = "sent_at"

    /** Quantisation of [KEY_SENT_AT] applied by the phone, so an unchanged reading
     *  republished within a bucket is an identical DataItem and never syncs. Must
     *  stay well under [tile.Pm25TileService.STALE_AFTER_MS]. */
    const val SENT_AT_BUCKET_MS = 60_000L

    /** The phone's configured relay interval, seconds. The watch derives its own
     *  staleness threshold from this: a 15-minute relay interval must not make a
     *  perfectly current reading look stale just because a fixed 10-minute
     *  threshold expired between two intended updates. */
    const val KEY_RELAY_SECONDS = "relay_seconds"

    /** Downsampled PM2.5 series over the last hour (oldest -> newest, ug/m3), for
     *  the Tile sparkline. May be empty. */
    const val KEY_SPARK_PM25 = "spark_pm25"
}
