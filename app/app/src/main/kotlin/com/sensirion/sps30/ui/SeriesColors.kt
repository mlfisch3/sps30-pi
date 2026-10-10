package com.sensirion.sps30.ui

import androidx.compose.ui.graphics.Color

/**
 * Per-series colours and labels for the four PM mass concentrations, mirroring
 * the Textual client's palette (pm1.0 pale yellow, pm2.5 green, pm4.0 red,
 * pm10 cyan) so the two front-ends read the same.
 */
object SeriesColors {
    val color: Map<String, Color> = mapOf(
        "pm1_0" to Color(0xFFEEE8AA), // pale yellow (palegoldenrod)
        "pm2_5" to Color(0xFF2ECC40), // green
        "pm4_0" to Color(0xFFFF4136), // red
        "pm10" to Color(0xFF39CCCC),  // cyan
    )

    val label: Map<String, String> = mapOf(
        "pm1_0" to "PM1.0",
        "pm2_5" to "PM2.5",
        "pm4_0" to "PM4.0",
        "pm10" to "PM10",
    )

    val unit: String = "µg/m³"
}
