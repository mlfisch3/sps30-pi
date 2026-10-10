package com.sensirion.sps30.data

/** Kotlin port of `chartview.parse_duration`. */
object Durations {

    private val UNIT_SECONDS: Map<String, Double> = mapOf(
        "s" to 1.0, "sec" to 1.0, "secs" to 1.0, "second" to 1.0, "seconds" to 1.0,
        "m" to 60.0, "min" to 60.0, "mins" to 60.0, "minute" to 60.0, "minutes" to 60.0,
        "h" to 3600.0, "hr" to 3600.0, "hrs" to 3600.0, "hour" to 3600.0, "hours" to 3600.0,
        "d" to 86400.0, "day" to 86400.0, "days" to 86400.0,
    )

    private val TOKEN = Regex("""(\d+(?:\.\d+)?)([a-z]+)""")
    private val WHITESPACE = Regex("""\s+""")
    // Strict decimal/scientific number. Guards the bare-number shortcut against
    // Kotlin/Java accepting a trailing d/D/f/F suffix (so "1d" is days, not 1.0).
    private val PURE_NUMBER = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")

    /**
     * Human duration -> seconds (> 0). Accepts a bare number as seconds
     * ("1800"), unit suffixes (s/m/h/d and long spellings), combinations
     * ("1h30m"), and spaced word units ("12 hours"). Case-insensitive.
     * Throws [IllegalArgumentException] on empty/unparseable/non-positive input.
     */
    fun parse(text: String?): Double {
        if (text == null) throw IllegalArgumentException("duration is required")
        val stripped = text.trim().lowercase()
        if (stripped.isEmpty()) throw IllegalArgumentException("empty duration")

        // A bare number (no unit) is seconds - but only if it is purely numeric,
        // so a unit token like "1d" is not swallowed by the d/f suffix.
        if (PURE_NUMBER.matches(stripped)) {
            val seconds = stripped.toDouble()
            if (seconds <= 0) throw IllegalArgumentException("non-positive duration: $text")
            return seconds
        }

        val compact = stripped.replace(WHITESPACE, "")
        var total = 0.0
        var pos = 0
        var matched = false
        for (m in TOKEN.findAll(compact)) {
            if (m.range.first != pos) throw IllegalArgumentException("unparseable duration: $text")
            val unit = m.groupValues[2]
            val unitSeconds = UNIT_SECONDS[unit]
                ?: throw IllegalArgumentException("unknown duration unit '$unit' in $text")
            total += m.groupValues[1].toDouble() * unitSeconds
            pos = m.range.last + 1
            matched = true
        }
        if (!matched || pos != compact.length) {
            throw IllegalArgumentException("unparseable duration: $text")
        }
        if (total <= 0) throw IllegalArgumentException("non-positive duration: $text")
        return total
    }
}
