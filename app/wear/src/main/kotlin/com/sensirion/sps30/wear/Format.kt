package com.sensirion.sps30.wear

/** Shared number/unit formatting for every watch surface (app, Tile, complication). */
object Format {
    /** PM mass-concentration unit, with the real Greek mu and a superscript 3:
     *  "μg/m³" = micrograms per cubic metre. */
    const val PM_UNIT = "μg/m³"

    /** The PM2.5 label, kept as one constant so all surfaces read identically. */
    const val PM_LABEL = "PM2.5"

    /** One decimal below 100, whole numbers above (a 3-digit value needs no tenths). */
    fun pm(v: Double): String =
        if (v >= 100) v.toInt().toString() else String.format("%.1f", v)

    /** e.g. "3.1 μg/m³" - the value with its unit. */
    fun pmWithUnit(v: Double): String = "${pm(v)} $PM_UNIT"
}
