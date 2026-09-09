// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `dumpsys batterystats` has an undocumented output format that varies by
 * vendor and Android version, and this parser was accepted knowing it will
 * need maintenance - see section C of
 * `docs/spine/specs/2026-09-09-state-filter-and-per-app-metrics-design.md`.
 *
 * The containment is what these tests are really about: every unrecognised
 * input must return null so the UI says "unavailable", because a battery
 * figure that is quietly wrong is worse than one that is quietly missing. A
 * user restricting the wrong app on bad numbers gets nothing back and breaks
 * something they were using.
 *
 * `batterystats_charged.txt` is a verbatim prefix of
 * `dumpsys batterystats --charged` from the Pixel 10 Pro verification device
 * on Android 17 (API 37), truncated after the last `UID` line. It carries no
 * package names - only opaque uids - so it exposes nothing about which apps
 * the capture device has installed.
 */
class BatteryStatsParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture $name"
        }.bufferedReader().use { it.readText() }

    private val real: String get() = fixture("batterystats_charged.txt")

    @Test
    fun `reads the drain total off a real capture`() {
        val use = checkNotNull(parseBatteryPowerUse(real))

        // "Capacity: 4772, Computed drain: 355, actual drain: 355"
        assertEquals(355.0, use.computedDrainMah, 0.001)
        assertEquals(4772.0, use.capacityMah, 0.001)
    }

    @Test
    fun `reads the window off a real capture`() {
        val use = checkNotNull(parseBatteryPowerUse(real))

        // "Time on battery: 51m 46s 370ms (100.0%) realtime, ..."
        assertEquals(51 * 60_000L + 46_000L + 370L, use.timeOnBatteryMillis)
    }

    @Test
    fun `reads per-uid drain off a real capture`() {
        val use = checkNotNull(parseBatteryPowerUse(real))

        // "UID u0a644: 39.8 fg: ..." - user 0, appId 644, so uid 10644.
        assertEquals(39.8, use.mahByUid.getValue(10644), 0.001)
        // "UID u0a145: 1.69 bg: ..."
        assertEquals(1.69, use.mahByUid.getValue(10145), 0.001)
        // Bare numbers are system uids and are kept as-is: "UID 1000: 16.2".
        assertEquals(16.2, use.mahByUid.getValue(1000), 0.001)
        assertEquals(9.06, use.mahByUid.getValue(0), 0.001)
    }

    @Test
    fun `keeps every uid the capture reports`() {
        val use = checkNotNull(parseBatteryPowerUse(real))

        // 152 UID lines in the fixture. An off-by-one in the section scan
        // would silently drop the first or last app, and the first one is the
        // biggest drain on the device - the single row a user most wants.
        assertEquals(152, use.mahByUid.size)
    }

    @Test
    fun `expresses a uid as a percentage of computed drain`() {
        val use = checkNotNull(parseBatteryPowerUse(real))

        // 39.8 of 355 mAh.
        assertEquals(11.2, use.percentOf(10644)!!, 0.05)
        assertNull(use.percentOf(123456))
    }

    @Test
    fun `does not confuse the global block for a uid`() {
        val use = checkNotNull(parseBatteryPowerUse(real))

        // The section opens with a "Global" block whose lines look similar
        // ("screen: 17.5 apps: 17.5"). Attributing any of that to an app
        // would inflate one row by the device's whole screen cost.
        assertTrue(use.mahByUid.keys.all { it >= 0 })
        assertEquals(null, use.mahByUid[-1])
    }

    @Test
    fun `returns null when the power-use section is absent`() {
        // A real dump from a device that has been charging: every other
        // section is present and only this one is missing.
        val noSection = """
            Battery History (0% used, 0KB used of 262KB, 0 strings using 0):
            Statistics since last charge:
              Time on battery: 51m 46s 370ms (100.0%) realtime, 44m 44s 113ms (86.4%) uptime
              Discharge: 380 mAh
              All kernel wake locks:
        """.trimIndent()

        assertNull(parseBatteryPowerUse(noSection))
    }

    @Test
    fun `returns null when the drain total cannot be read`() {
        // Per-uid numbers with no total are useless: a percentage needs a
        // denominator, and inventing one would be a fabricated figure.
        val noTotal = """
            Statistics since last charge:
              Time on battery: 51m 46s 370ms (100.0%) realtime, 44m 44s 113ms (86.4%) uptime
              Estimated power use (mAh):
                Global
                screen: 17.5 apps: 17.5
              UID u0a644: 39.8 fg: 5.54 (5m 20s 905ms)
        """.trimIndent()

        assertNull(parseBatteryPowerUse(noTotal))
    }

    @Test
    fun `returns null when the section is present but reports no uid at all`() {
        // On a real device this never happens: the platform's own uids always
        // draw power. An empty result therefore means the format moved, not
        // that nothing used any battery - the same rule PolicyReader applies
        // to an empty deviceidle whitelist.
        val noUids = """
            Statistics since last charge:
              Time on battery: 51m 46s 370ms (100.0%) realtime, 44m 44s 113ms (86.4%) uptime
              Estimated power use (mAh):
                Capacity: 4772, Computed drain: 355, actual drain: 355
                Global
                screen: 17.5 apps: 17.5
              All kernel wake locks:
        """.trimIndent()

        assertNull(parseBatteryPowerUse(noUids))
    }

    @Test
    fun `returns null on truncated output`() {
        // A shell read that died mid-stream. Parsing the surviving head would
        // report a partial device as a whole one.
        assertNull(parseBatteryPowerUse(real.take(400)))
    }

    @Test
    fun `returns null on empty or unrelated input`() {
        assertNull(parseBatteryPowerUse(""))
        assertNull(parseBatteryPowerUse("   \n  \n"))
        assertNull(parseBatteryPowerUse("Permission Denial: can't dump batterystats"))
    }

    @Test
    fun `skips a uid form it does not understand rather than failing the whole read`() {
        // Isolated-process uids ("u0i42") and any future prefix are not app
        // uids and cannot be mapped to a package. One unknown line must not
        // cost the user every other app's number.
        val mixed = """
            Statistics since last charge:
              Time on battery: 51m 46s 370ms (100.0%) realtime, 44m 44s 113ms (86.4%) uptime
              Estimated power use (mAh):
                Capacity: 4772, Computed drain: 355, actual drain: 355
                Global
                screen: 17.5 apps: 17.5
              UID u0i42: 5.00 bg: 5.00
              UID u0a644: 39.8 fg: 5.54 (5m 20s 905ms)
              UID 1000: 16.2 bg: 16.2
        """.trimIndent()

        val use = checkNotNull(parseBatteryPowerUse(mixed))
        assertEquals(setOf(10644, 1000), use.mahByUid.keys)
    }

    @Test
    fun `maps a secondary user's app uid onto the right uid`() {
        // u10a5 is appId 5 in user 10, i.e. uid 1010005. Treating the user
        // segment as zero would attribute a work-profile app's drain to a
        // completely different package in the primary user.
        val secondaryUser = """
            Statistics since last charge:
              Time on battery: 1m 0s 0ms (100.0%) realtime, 1m 0s 0ms (100.0%) uptime
              Estimated power use (mAh):
                Capacity: 4772, Computed drain: 355, actual drain: 355
                Global
                screen: 17.5 apps: 17.5
              UID u10a5: 2.00 bg: 2.00
        """.trimIndent()

        val use = checkNotNull(parseBatteryPowerUse(secondaryUser))
        assertEquals(setOf(1010005), use.mahByUid.keys)
    }
}
