// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.metrics

import java.util.Locale
import kotlin.math.roundToLong

/**
 * How the two numbers are worded.
 *
 * Kept out of the composables so the wording is unit-testable: "unavailable"
 * versus a plausible-looking zero is the distinction this whole feature rests
 * on, and it should not only be verifiable by screenshotting a device.
 */
object MetricFormat {

    /** `12%`, `0.4%`, or "unavailable" when there is no figure. */
    fun battery(percent: Double?): String = when {
        percent == null -> UNAVAILABLE
        // Below a tenth of a percent, one decimal reads as 0.0% and looks
        // like a measured nothing. "<0.1%" says it was measured and is tiny.
        percent < 0.1 -> "<0.1%"
        percent < 10.0 -> String.format(Locale.US, "%.1f%%", percent)
        else -> "${percent.roundToLong()}%"
    }

    /** `3h 20m`, `45s`, `none`, or "unavailable" when there is no figure. */
    fun runtime(millis: Long?): String {
        if (millis == null) return UNAVAILABLE
        // A measured zero is a real, useful answer - "you have not opened this
        // app since you unplugged" is the strongest case for restricting it -
        // so it must not read the same as no measurement at all.
        if (millis <= 0L) return "none"

        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    /** The one-line pair shown on a row: `12% · 3h 20m`. */
    fun rowSummary(metric: AppMetric?): String =
        "${battery(metric?.batteryPercent)} · ${runtime(metric?.foregroundMillis)}"

    /**
     * States the window the numbers cover. Without it, everything looks
     * reassuringly small right after the phone comes off the charger.
     */
    fun window(window: MetricsWindow?): String = when {
        window == null -> "no measurement window"
        window.sinceLastCharge -> "since last charge, ${runtime(window.millis)} ago"
        // The battery read failed, so runtime is on a fallback window. Saying
        // which one keeps the numbers honest rather than implying they match
        // the battery figures they are sitting next to.
        else -> "last ${runtime(window.millis)}, battery unavailable"
    }

    const val UNAVAILABLE = "unavailable"
}
