package com.sensirion.sps30.wear

import androidx.annotation.ColorInt

/**
 * US EPA 24-hour PM2.5 air-quality categories, used to color and label the
 * complication. Breakpoints in ug/m3.
 */
enum class AirQuality(val label: String, @ColorInt val color: Int) {
    GOOD("Good", 0xFF2ECC71.toInt()),
    MODERATE("Moderate", 0xFFF1C40F.toInt()),
    SENSITIVE("Unhealthy (sensitive)", 0xFFE67E22.toInt()),
    UNHEALTHY("Unhealthy", 0xFFE74C3C.toInt()),
    VERY_UNHEALTHY("Very unhealthy", 0xFF9B59B6.toInt()),
    HAZARDOUS("Hazardous", 0xFF7E0023.toInt());

    companion object {
        /** Upper bound of the ranged-value ring: the top of the "Unhealthy" band,
         *  so a common indoor-to-outdoor spread maps across the whole arc. */
        const val RING_MAX = 150.4f

        fun of(pm25: Double): AirQuality = when {
            pm25 <= 12.0 -> GOOD
            pm25 <= 35.4 -> MODERATE
            pm25 <= 55.4 -> SENSITIVE
            pm25 <= 150.4 -> UNHEALTHY
            pm25 <= 250.4 -> VERY_UNHEALTHY
            else -> HAZARDOUS
        }

        /** Gradient stops for the ranged-value color ramp, Good -> Unhealthy. */
        val RAMP: IntArray = intArrayOf(
            GOOD.color, MODERATE.color, SENSITIVE.color, UNHEALTHY.color,
        )
    }
}
