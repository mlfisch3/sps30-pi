package com.sensirion.sps30.wear.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.sensirion.sps30.wear.AirQuality
import com.sensirion.sps30.wear.Format
import com.sensirion.sps30.wear.MainActivity
import com.sensirion.sps30.wear.relay.LatestReading
import com.sensirion.sps30.wear.relay.LatestReadingStore
import kotlin.math.roundToInt

/**
 * A glanceable Tile: the "PM2.5" label (kept distinct and apart from the number),
 * the current value + "μg/m³", a 1-hour sparkline (rendered from the phone-relayed
 * series by [SparklineRenderer] and embedded as an inline image), and the
 * air-quality label - all tinted by the current air-quality level. Tapping the
 * Tile opens the watch app (the same readout).
 *
 * Reads only the phone-relayed value from [LatestReadingStore]; the Data Layer
 * listener calls [TileService.getUpdater] on each relay to keep it live.
 */
class Pm25TileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = immediate {
        val reading = LatestReadingStore.load(this)
        val version = (reading?.sentAt ?: 0L).toString()
        TileBuilders.Tile.Builder()
            .setResourcesVersion(version)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(reading)))
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = immediate {
        val reading = LatestReadingStore.load(this)
        val density = resources.displayMetrics.density
        val wPx = (SPARK_W_DP * density).roundToInt()
        val hPx = (SPARK_H_DP * density).roundToInt()
        val img = SparklineRenderer.render(reading?.spark ?: FloatArray(0), wPx, hPx)

        ResourceBuilders.Resources.Builder()
            .setVersion(requestParams.version)
            .addIdToImageMapping(
                SPARK_ID,
                ResourceBuilders.ImageResource.Builder()
                    .setInlineResource(
                        ResourceBuilders.InlineImageResource.Builder()
                            .setData(img.bytes)
                            .setWidthPx(img.widthPx)
                            .setHeightPx(img.heightPx)
                            .setFormat(ResourceBuilders.IMAGE_FORMAT_RGB_565)
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun layout(reading: LatestReading?): LayoutElementBuilders.LayoutElement {
        val pm = reading?.pm2_5
        val hasValue = pm != null && !pm.isNaN()
        val fresh = hasValue &&
            System.currentTimeMillis() - (reading?.sentAt ?: 0L) <= staleAfterMs(reading)
        val aq = AirQuality.of(pm ?: 0.0)
        val big = if (hasValue && fresh) Format.pm(pm!!) else "--"
        val label = when {
            !hasValue -> "No data"
            !fresh -> "Stale"
            else -> aq.label
        }
        val color = if (hasValue && fresh) aq.color else MUTED

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            // Label first, muted and small, then a gap - kept clearly apart from the
            // number so it never reads as part of the value.
            .addContent(
                Text.Builder(this, Format.PM_LABEL)
                    .setColor(argb(MUTED))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION3)
                    .build(),
            )
            .addContent(spacer(4f))
            .addContent(
                Text.Builder(this, big)
                    .setColor(argb(color))
                    .setTypography(Typography.TYPOGRAPHY_DISPLAY1)
                    .build(),
            )
            .addContent(
                Text.Builder(this, Format.PM_UNIT)
                    .setColor(argb(MUTED))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION3)
                    .build(),
            )
            .addContent(spacer(4f))
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(SPARK_ID)
                    .setWidth(DimensionBuilders.dp(SPARK_W_DP))
                    .setHeight(DimensionBuilders.dp(SPARK_H_DP))
                    .build(),
            )
            .addContent(
                Text.Builder(this, label)
                    .setColor(argb(color))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .build(),
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open")
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName(MainActivity::class.java.name)
                                            .build(),
                                    )
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .addContent(column)
            .build()
    }

    private fun spacer(dp: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(dp)).build()

    private fun <T> immediate(block: () -> T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(block())
            "Pm25Tile"
        }

    private companion object {
        const val SPARK_ID = "spark"
        const val SPARK_W_DP = 120f
        const val SPARK_H_DP = 34f
        const val MUTED = 0xFFB0B0B0.toInt()
        const val FRESHNESS_MS = 5 * 60 * 1000L
        const val STALE_AFTER_MS = 10 * 60 * 1000L

        /**
         * How old a reading may be before it counts as stale.
         *
         * Derived from the phone's own relay interval rather than fixed: at a
         * 15-minute interval a perfectly current reading would otherwise be
         * marked stale simply because a fixed 10-minute threshold expired
         * between two intended updates. Allow two intervals plus slack, and
         * never less than the fixed floor.
         */
        fun staleAfterMs(reading: LatestReading?): Long {
            val relayMs = (reading?.relaySeconds ?: 0).toLong() * 1000L
            if (relayMs <= 0L) return STALE_AFTER_MS
            return maxOf(STALE_AFTER_MS, relayMs * 2 + 60_000L)
        }
    }
}
