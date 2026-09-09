// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wording carries the feature's central distinction: an unmeasured number
 * must never read as a measured zero. A user restricting apps off these
 * figures acts on the wording, not on the nullability.
 */
class MetricFormatTest {

    @Test
    fun `no battery figure reads as unavailable, never as zero`() {
        assertEquals("unavailable", MetricFormat.battery(null))
    }

    @Test
    fun `a tiny but measured battery figure says it was measured`() {
        // 0.0% would look like a measured nothing, which is the one reading
        // this must not produce.
        assertEquals("<0.1%", MetricFormat.battery(0.04))
        assertEquals("0.5%", MetricFormat.battery(0.5))
    }

    @Test
    fun `battery keeps one decimal below ten percent and rounds above`() {
        assertEquals("9.4%", MetricFormat.battery(9.44))
        assertEquals("11%", MetricFormat.battery(11.2))
        assertEquals("100%", MetricFormat.battery(100.0))
    }

    @Test
    fun `no runtime figure reads as unavailable`() {
        assertEquals("unavailable", MetricFormat.runtime(null))
    }

    @Test
    fun `a measured zero runtime is its own answer`() {
        // "You have not opened this app since you unplugged" is the strongest
        // case for restricting it, so it must not be confused with "we could
        // not measure this app".
        assertEquals("none", MetricFormat.runtime(0L))
    }

    @Test
    fun `runtime drops to the coarsest useful unit`() {
        assertEquals("45s", MetricFormat.runtime(45_000L))
        assertEquals("3m", MetricFormat.runtime(3 * 60_000L))
        assertEquals("3h 20m", MetricFormat.runtime(3 * 3_600_000L + 20 * 60_000L))
    }

    @Test
    fun `a row summary degrades one half at a time`() {
        assertEquals(
            "unavailable · 3h 20m",
            MetricFormat.rowSummary(AppMetric(foregroundMillis = 3 * 3_600_000L + 20 * 60_000L)),
        )
        assertEquals(
            "11% · unavailable",
            MetricFormat.rowSummary(AppMetric(batteryPercent = 11.2)),
        )
        assertEquals("unavailable · unavailable", MetricFormat.rowSummary(null))
    }

    @Test
    fun `the window is stated, so small numbers are readable`() {
        assertEquals(
            "since last charge, 51m ago",
            MetricFormat.window(MetricsWindow(51 * 60_000L, sinceLastCharge = true)),
        )
    }

    @Test
    fun `a fallback window says the battery figure is missing`() {
        // Runtime survives a failed battery read on a fixed window. Saying so
        // stops those numbers from being read as matching battery figures
        // that do not exist.
        assertEquals(
            "last 24h 0m, battery unavailable",
            MetricFormat.window(
                MetricsWindow(MetricsSnapshot.FALLBACK_WINDOW_MILLIS, sinceLastCharge = false),
            ),
        )
    }

    @Test
    fun `no window at all is said out loud`() {
        assertEquals("no measurement window", MetricFormat.window(null))
    }
}
