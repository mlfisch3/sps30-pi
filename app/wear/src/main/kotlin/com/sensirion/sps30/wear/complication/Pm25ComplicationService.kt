package com.sensirion.sps30.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.sensirion.sps30.wear.AirQuality
import com.sensirion.sps30.wear.Format
import com.sensirion.sps30.wear.MainActivity
import com.sensirion.sps30.wear.R
import com.sensirion.sps30.wear.relay.LatestReadingStore
import com.sensirion.sps30.wear.tile.SparklineRenderer

/**
 * The PM2.5 complication, rendered as a labelled text value ("PM2.5" title +
 * "3.1 μg/m³" value) rather than a value-on-a-scale ring - it reads like a
 * "Feels like 71°" weather complication, not a gauge.
 *
 * Where the watch-face slot can show an image (SHORT_TEXT/LONG_TEXT with a small
 * image), it also carries a tiny 1-hour sparkline so the recent trend is visible
 * at a glance - which is what earns it a slot over a static number. Faces that do
 * not render the image fall back to the monochrome particle icon. Tapping opens
 * the watch app, which shows the full-size sparkline view (the same picture as the
 * Tile).
 *
 * Data comes only from the phone relay via [LatestReadingStore]; no daemon
 * connection here.
 */
class Pm25ComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, pm25 = 18.0, spark = PREVIEW_SPARK, stale = false)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val latest = LatestReadingStore.load(this)
        val stale = latest == null ||
            System.currentTimeMillis() - latest.sentAt > STALE_AFTER_MS ||
            latest.pm2_5.isNaN()
        return build(request.complicationType, latest?.pm2_5, latest?.spark ?: FloatArray(0), stale)
    }

    private fun build(
        type: ComplicationType,
        pm25: Double?,
        spark: FloatArray,
        stale: Boolean,
    ): ComplicationData? {
        val hasValue = pm25 != null && !pm25.isNaN()
        val fresh = hasValue && !stale
        val value = pm25 ?: 0.0
        val aq = AirQuality.of(value)

        val valueText = if (fresh) Format.pmWithUnit(value) else "-- ${Format.PM_UNIT}"
        val title = PlainComplicationText.Builder(Format.PM_LABEL).build()
        val text = PlainComplicationText.Builder(valueText).build()
        val description = PlainComplicationText.Builder(
            when {
                !hasValue -> getString(R.string.no_data)
                stale -> "${Format.PM_LABEL} ${Format.pmWithUnit(value)} (stale)"
                else -> "${Format.PM_LABEL} ${Format.pmWithUnit(value)} - ${aq.label}"
            },
        ).build()

        val sparkImage = if (fresh && spark.size >= 2) sparkSmallImage(spark) else null
        val fallbackIcon = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_pm),
        ).build()

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text, description)
                .setTitle(title)
                .also { b ->
                    if (sparkImage != null) b.setSmallImage(sparkImage)
                    else b.setMonochromaticImage(fallbackIcon)
                }
                .setTapAction(tapAction())
                .build()

            // Value only, no label/title: on a one-line LONG_TEXT slot (e.g. curved
            // edge) a "PM2.5" label sits inline at the same size and reads as part of
            // the number - and the face controls font/size/position so we cannot make
            // it subordinate. The unit already identifies the reading; the label lives
            // on SHORT_TEXT (as a title) and in the tap-through app.
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text, description)
                .also { b ->
                    if (sparkImage != null) b.setSmallImage(sparkImage)
                    else b.setMonochromaticImage(fallbackIcon)
                }
                .setTapAction(tapAction())
                .build()

            else -> null
        }
    }

    /** The 1-hour sparkline as a full-color small image (PHOTO). */
    private fun sparkSmallImage(spark: FloatArray): SmallImage {
        val bitmap = SparklineRenderer.renderBitmap(spark, SPARK_PX, SPARK_PX)
        return SmallImage.Builder(Icon.createWithBitmap(bitmap), SmallImageType.PHOTO).build()
    }

    /** Tapping the complication opens the watch app (full sparkline readout). */
    private fun tapAction(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val STALE_AFTER_MS = 10 * 60 * 1000L
        const val SPARK_PX = 128
        val PREVIEW_SPARK = floatArrayOf(
            9f, 10f, 12f, 11f, 13f, 16f, 15f, 18f, 17f, 19f, 18f, 20f,
        )
    }
}
