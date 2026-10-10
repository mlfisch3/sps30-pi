package com.sensirion.sps30.data

import kotlinx.serialization.Serializable

/**
 * One threshold and what should happen when the air crosses it.
 *
 * Levels are independent: any combination may be enabled or none at all, so a
 * user who wants no interruptions can have exactly that. Each rule owns its own
 * reactions rather than there being a fixed escalation order, because "vibrate
 * at 35 but stay silent at 20" and "notify at 20 and also sound at 20" are both
 * reasonable and neither is more correct.
 *
 * Thresholds deliberately live only on the phone. They exist to notify *this*
 * user, so putting them on the shared controller would either impose one
 * person's idea of "high" on everyone or require per-client state the sensor has
 * no reason to carry.
 */
@Serializable
data class AlertRule(
    val id: String,
    val label: String,
    /** ug/m3 at or above which this rule fires. */
    val threshold: Double,
    /** Which PM series is watched. PM2.5 is the health-relevant default. */
    val field: String = "pm2_5",
    val enabled: Boolean = true,
    val notify: Boolean = true,
    val vibrate: Boolean = false,
    val sound: Boolean = false,
    val watch: Boolean = false,
    /** Shade the plot above this threshold. */
    val highlightPlot: Boolean = true,
) {
    /** True if any reaction at all would happen; a rule with none is inert. */
    val hasReaction: Boolean get() = notify || vibrate || sound || watch || highlightPlot
}

/**
 * Tracks which rules are currently breached and reports only the *transitions*.
 *
 * Readings arrive up to once a second, so firing on every sample above the line
 * would be unusable. A rule fires once on the way up and cannot fire again until
 * the reading has fallen clear of the threshold by [HYSTERESIS_FRACTION] - air
 * hovering exactly on a threshold is the normal case, not an edge case, and
 * without hysteresis it would alert continuously.
 *
 * Pure and clock-injected so the whole escalation chain is testable without
 * waiting for bad air.
 */
class AlertEngine(private val nowMs: () -> Long = System::currentTimeMillis) {

    private val breached = HashSet<String>()
    private val lastFiredMs = HashMap<String, Long>()

    /** Rules currently in the breached state, for painting the UI. */
    val active: Set<String> get() = breached.toSet()

    /**
     * Fold in one reading and return the rules that have *just* become breached.
     * Rules that are still breached from before return nothing.
     */
    fun evaluate(rules: List<AlertRule>, values: Map<String, Double>): List<AlertRule> {
        val fired = ArrayList<AlertRule>()
        val known = rules.mapTo(HashSet()) { it.id }
        // Drop state for rules the user has deleted, so an id reused later starts clean.
        breached.retainAll(known)
        lastFiredMs.keys.retainAll(known)

        for (rule in rules) {
            if (!rule.enabled || !rule.hasReaction) {
                breached.remove(rule.id)
                continue
            }
            val value = values[rule.field] ?: continue
            val wasBreached = rule.id in breached
            if (!wasBreached && value >= rule.threshold) {
                breached.add(rule.id)
                val last = lastFiredMs[rule.id]
                val now = nowMs()
                if (last == null || now - last >= COOLDOWN_MS) {
                    lastFiredMs[rule.id] = now
                    fired.add(rule)
                }
            } else if (wasBreached && value < rule.threshold * (1 - HYSTERESIS_FRACTION)) {
                breached.remove(rule.id)
            }
        }
        return fired
    }

    /** Force [rule] to fire next evaluation - used by the test-alert button. */
    fun rearm(rule: AlertRule) {
        breached.remove(rule.id)
        lastFiredMs.remove(rule.id)
    }

    fun reset() {
        breached.clear()
        lastFiredMs.clear()
    }

    companion object {
        /** A rule re-arms once the reading falls this far below its threshold. */
        const val HYSTERESIS_FRACTION = 0.10

        /** Never fire the same rule more often than this, however the air behaves. */
        const val COOLDOWN_MS = 5 * 60 * 1000L

        /**
         * The out-of-the-box rule. A fresh install should not be silent about air
         * genuinely worth knowing about, and 20 ug/m3 PM2.5 sits above typical
         * indoor background while staying below the 24-hour guideline levels that
         * health bodies treat as concerning - high enough not to cry wolf, low
         * enough to catch cooking, smoke and similar.
         */
        fun defaults(): List<AlertRule> = listOf(
            AlertRule(
                id = "elevated",
                label = "Elevated",
                threshold = 20.0,
                notify = true,
                highlightPlot = true,
            ),
            AlertRule(
                id = "high",
                label = "High",
                threshold = 55.0,
                notify = true,
                vibrate = true,
                enabled = false,
            ),
            AlertRule(
                id = "very-high",
                label = "Very high",
                threshold = 150.0,
                notify = true,
                vibrate = true,
                sound = true,
                enabled = false,
            ),
        )
    }
}
