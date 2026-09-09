// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.metrics

/**
 * What the two new numbers on a row are worth, per package.
 *
 * Both fields are nullable and null always means "no figure", never "zero".
 * The distinction is the whole point: an app with no measurement must not
 * present itself as the cleanest one on the device, because that is exactly
 * the app a user would then leave alone.
 */
data class AppMetric(
    /** Share of the window's estimated drain, 0-100, or null when unmeasured. */
    val batteryPercent: Double? = null,
    /** Estimated milliampere-hours, or null when unmeasured. */
    val batteryMah: Double? = null,
    /** Foreground time in the window, or null when usage access is absent. */
    val foregroundMillis: Long? = null,
    /**
     * True when this package shares its uid with at least one other installed
     * package. `batterystats` attributes power to uids, so the battery figure
     * then covers the whole group and is not this package's alone. Surfaced in
     * the UI rather than hidden, because the number is otherwise a lie about
     * this row.
     */
    val batteryIsSharedUid: Boolean = false,
)

/**
 * The window both metrics cover, so the UI can state it. A number without its
 * window is unreadable: ten minutes after unplugging, every app looks clean.
 */
data class MetricsWindow(
    val millis: Long,
    /**
     * True when the window came from `batterystats`' own time-on-battery, so
     * both metrics are directly comparable. False means the battery read
     * failed and runtime fell back to a fixed recent window - runtime must not
     * disappear just because the fragile parser did.
     */
    val sinceLastCharge: Boolean,
)

data class MetricsSnapshot(
    val window: MetricsWindow,
    val byPackage: Map<String, AppMetric> = emptyMap(),
    /**
     * Set when the `GET_USAGE_STATS` appop has not been granted, so the UI can
     * offer the one-time prompt instead of showing runtime as merely missing.
     */
    val needsUsageAccess: Boolean = false,
) {
    fun of(packageName: String): AppMetric? = byPackage[packageName]

    companion object {
        /** The window runtime falls back to when time-on-battery cannot be read. */
        const val FALLBACK_WINDOW_MILLIS = 24 * 60 * 60 * 1000L

        /** Nothing measured at all - both metrics unavailable, window unknown. */
        fun unavailable(): MetricsSnapshot = MetricsSnapshot(
            window = MetricsWindow(millis = FALLBACK_WINDOW_MILLIS, sinceLastCharge = false),
        )
    }
}
