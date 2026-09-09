// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.metrics

import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section B and D of the 1.1.0 design. [MetricsReader] never fails, unlike
 * `PolicyReader`: these two numbers are advisory, so losing them must degrade
 * a row rather than blank the screen.
 */
class MetricsReaderTest {

    private val game = InstalledApp("com.example.game", "Game", 10644, isSystem = false, isEnabled = true)
    private val chat = InstalledApp("com.example.chat", "Chat", 10145, isSystem = false, isEnabled = true)
    private val unmeasured = InstalledApp("com.example.quiet", "Quiet", 10999, isSystem = false, isEnabled = true)
    private val apps = listOf(game, chat, unmeasured)

    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/batterystats_charged.txt"))
            .bufferedReader().use { it.readText() }

    private fun shell(batteryOk: Boolean = true) = FakeShellBackend().apply {
        script(
            "dumpsys batterystats",
            if (batteryOk) ShellResult(0, fixture(), "") else ShellResult(1, "", "Permission Denial"),
        )
        script("appops set", ShellResult(0, "", ""))
    }

    private fun reader(
        batteryOk: Boolean = true,
        usage: UsageSource = FakeUsageSource(),
        now: Long = 1_000_000_000L,
    ) = MetricsReader(shell(batteryOk), usage, now = { now })

    @Test
    fun `attributes battery to the package holding that uid`() = runTest {
        val snapshot = reader().read(apps)

        // uid 10644 is "UID u0a644: 39.8" in the fixture, 39.8 of 355 mAh.
        val metric = checkNotNull(snapshot.of("com.example.game"))
        assertEquals(39.8, metric.batteryMah!!, 0.001)
        assertEquals(11.2, metric.batteryPercent!!, 0.05)
    }

    @Test
    fun `an unmeasured package gets null battery, not zero`() = runTest {
        val snapshot = reader().read(apps)

        // uid 10999 appears nowhere in the capture. Reporting 0% would put
        // this app forward as the cleanest on the device - the one row a user
        // would then leave alone.
        val metric = checkNotNull(snapshot.of("com.example.quiet"))
        assertNull(metric.batteryPercent)
        assertNull(metric.batteryMah)
    }

    @Test
    fun `takes the window from time on battery`() = runTest {
        val usage = FakeUsageSource()
        val snapshot = reader(usage = usage).read(apps)

        val expected = 51 * 60_000L + 46_000L + 370L
        assertEquals(expected, snapshot.window.millis)
        assertTrue(snapshot.window.sinceLastCharge)
        // The runtime query must cover the same window, or the two numbers on
        // a row stop being comparable.
        assertEquals(1_000_000_000L - expected, usage.lastStartMillis)
        assertEquals(1_000_000_000L, usage.lastEndMillis)
    }

    @Test
    fun `runtime survives a failed battery read`() = runTest {
        val usage = FakeUsageSource(foreground = mapOf("com.example.game" to 5_000L))
        val snapshot = reader(batteryOk = false, usage = usage).read(apps)

        // The fragile parser failing must not take the reliable metric with
        // it - section D of the design.
        assertEquals(5_000L, checkNotNull(snapshot.of("com.example.game")).foregroundMillis)
        assertNull(checkNotNull(snapshot.of("com.example.game")).batteryPercent)
        assertEquals(MetricsSnapshot.FALLBACK_WINDOW_MILLIS, snapshot.window.millis)
        assertFalse(snapshot.window.sinceLastCharge)
    }

    @Test
    fun `does not query usage without access, and says so`() = runTest {
        val usage = FakeUsageSource(access = false)
        val snapshot = reader(usage = usage).read(apps)

        assertEquals(0, usage.queryCount)
        assertTrue(snapshot.needsUsageAccess)
        assertNull(checkNotNull(snapshot.of("com.example.game")).foregroundMillis)
        // Battery is unaffected: declining the usage prompt costs one metric,
        // not both.
        assertEquals(39.8, checkNotNull(snapshot.of("com.example.game")).batteryMah!!, 0.001)
    }

    @Test
    fun `an app absent from a readable window is a measured zero`() = runTest {
        val usage = FakeUsageSource(foreground = mapOf("com.example.game" to 5_000L))
        val snapshot = reader(usage = usage).read(apps)

        // Access granted and the window read, so "chat was never in the
        // foreground" is a measurement - distinct from the null above, where
        // nothing could be measured at all.
        assertEquals(0L, checkNotNull(snapshot.of("com.example.chat")).foregroundMillis)
    }

    @Test
    fun `an unreadable usage window is null, not zero`() = runTest {
        val usage = FakeUsageSource(foreground = null)
        val snapshot = reader(usage = usage).read(apps)

        assertNull(checkNotNull(snapshot.of("com.example.game")).foregroundMillis)
        // Access is granted, so there is no prompt to offer - this is a read
        // failure, not a permission problem.
        assertFalse(snapshot.needsUsageAccess)
    }

    @Test
    fun `flags a battery figure that covers a shared uid`() = runTest {
        val twin = InstalledApp("com.example.game.twin", "Game Twin", 10644, isSystem = false, isEnabled = true)
        val snapshot = reader().read(apps + twin)

        // batterystats attributes power to uids. Both packages carry the
        // group's 39.8 mAh, flagged, because claiming it as either one's own
        // would be a lie about that row.
        assertTrue(checkNotNull(snapshot.of("com.example.game")).batteryIsSharedUid)
        assertTrue(checkNotNull(snapshot.of("com.example.game.twin")).batteryIsSharedUid)
        assertFalse(checkNotNull(snapshot.of("com.example.chat")).batteryIsSharedUid)
    }

    @Test
    fun `an unmeasured uid is never flagged as shared`() = runTest {
        val twin = InstalledApp("com.example.quiet.twin", "Quiet Twin", 10999, isSystem = false, isEnabled = true)
        val snapshot = reader().read(apps + twin)

        // Both share uid 10999, but neither has a figure, so there is nothing
        // to caveat.
        assertFalse(checkNotNull(snapshot.of("com.example.quiet")).batteryIsSharedUid)
    }

    @Test
    fun `granting usage access runs the appop the platform actually checks`() = runTest {
        val backend = shell()
        val reader = MetricsReader(backend, FakeUsageSource())

        assertTrue(reader.grantUsageAccess("com.jinatra.hiberna"))

        assertEquals(
            listOf("cmd", "appops", "set", "com.jinatra.hiberna", "GET_USAGE_STATS", "allow"),
            backend.executed.last(),
        )
    }

    @Test
    fun `revoking usage access is the exact inverse`() = runTest {
        val backend = shell()
        val reader = MetricsReader(backend, FakeUsageSource())

        assertTrue(reader.revokeUsageAccess("com.jinatra.hiberna"))

        assertEquals(
            listOf("cmd", "appops", "set", "com.jinatra.hiberna", "GET_USAGE_STATS", "deny"),
            backend.executed.last(),
        )
    }

    @Test
    fun `a shell that refuses everything still returns a usable snapshot`() = runTest {
        // The privilege gate being shut is a first-class state in this app,
        // never an error dialog. Metrics are advisory, so they degrade.
        val snapshot = MetricsReader(FakeShellBackend(isAvailable = false), FakeUsageSource(access = false))
            .read(apps)

        assertEquals(apps.size, snapshot.byPackage.size)
        assertTrue(snapshot.byPackage.values.all { it.batteryPercent == null && it.foregroundMillis == null })
    }
}
