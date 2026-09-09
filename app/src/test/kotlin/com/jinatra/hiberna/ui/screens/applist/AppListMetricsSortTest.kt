// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jinatra.hiberna.apps.FakeAppRepository
import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.apps.InstalledAppRepository
import com.jinatra.hiberna.guardrail.FakeSensitivityDetector
import com.jinatra.hiberna.metrics.FakeUsageSource
import com.jinatra.hiberna.metrics.MetricsReader
import com.jinatra.hiberna.policy.BulkApplier
import com.jinatra.hiberna.policy.PolicyApplier
import com.jinatra.hiberna.policy.PolicyReader
import com.jinatra.hiberna.preset.DataStoreOverrideRepository
import com.jinatra.hiberna.preset.OverrideRepository
import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Sections E and F of the 1.1.0 design, driven through the real view model
 * against the real device capture rather than against a hand-written string,
 * so the sort is proven on the shape `batterystats` actually emits.
 *
 * The uids below are the ones in the fixture: `UID u0a644: 39.8` and
 * `UID u0a145: 1.69`, so uid 10644 is the heaviest app on that device and uid
 * 10145 a light one. Labels are chosen so alphabetical order disagrees with
 * every metric order - otherwise a passing test could not tell the two apart.
 */
class AppListMetricsSortTest {

    @get:Rule val tmp = TemporaryFolder()
    private var storeCounter = 0

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.root, "overrides-${storeCounter++}.preferences_pb") },
        )

    /** Heaviest battery in the fixture, alphabetically last. */
    private val heavy = InstalledApp("com.example.zebra", "Zebra", 10644, isSystem = false, isEnabled = true)

    /** Light battery in the fixture, alphabetically first. */
    private val light = InstalledApp("com.example.apple", "Apple", 10145, isSystem = false, isEnabled = true)

    /** Absent from the fixture entirely, alphabetically in the middle. */
    private val unmeasured = InstalledApp("com.example.mango", "Mango", 10999, isSystem = false, isEnabled = true)

    private val apps = listOf(heavy, light, unmeasured)

    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/batterystats_charged.txt"))
            .bufferedReader().use { it.readText() }

    private fun policyShell() = FakeShellBackend().apply {
        script("appops query-op", ShellResult(0, "", ""))
        script("deviceidle whitelist", ShellResult(0, "user,com.example.zebra,10644", ""))
        script("netpolicy list", ShellResult(0, "", ""))
    }

    private fun metricsShell() = FakeShellBackend().apply {
        script("dumpsys batterystats", ShellResult(0, fixture(), ""))
    }

    private fun vm(
        foreground: Map<String, Long>? = emptyMap(),
        usageGranted: Boolean = true,
    ): AppListViewModel = AppListViewModel(
        apps = FakeAppRepository(apps) as InstalledAppRepository,
        reader = PolicyReader(policyShell()),
        applier = PolicyApplier(policyShell(), FakeAppRepository(apps)),
        sensitivity = FakeSensitivityDetector(emptySet()),
        bulk = BulkApplier(PolicyApplier(policyShell(), FakeAppRepository(apps))),
        overrides = DataStoreOverrideRepository(store()) as OverrideRepository,
        metrics = MetricsReader(
            metricsShell(),
            FakeUsageSource(access = usageGranted, foreground = foreground),
        ),
    )

    private fun labels(model: AppListViewModel): List<String> =
        model.state.value.rows.map { it.app.label }

    @Test
    fun `carries the measured battery figure onto the right row`() = runTest {
        val model = vm()
        model.load()

        val rows = model.state.value.rows.associateBy { it.app.label }
        assertEquals(39.8, rows.getValue("Zebra").metric?.batteryMah!!, 0.001)
        assertEquals(1.69, rows.getValue("Apple").metric?.batteryMah!!, 0.001)
        // Absent from the capture: null, so the UI says "unavailable". Zero
        // would advertise it as the cleanest app on the device.
        assertNull(rows.getValue("Mango").metric?.batteryMah)
    }

    @Test
    fun `sorts by battery, heaviest first`() = runTest {
        val model = vm()
        model.load()
        model.onSortByChange(SortBy.BATTERY_DESC)

        // Zebra is alphabetically last and the heaviest drain, so an
        // accidental alphabetical sort cannot produce this order.
        assertEquals(listOf("Zebra", "Apple", "Mango"), labels(model))
    }

    @Test
    fun `an unmeasured app sorts last under battery, never as a clean zero`() = runTest {
        val model = vm()
        model.load()
        model.onSortByChange(SortBy.BATTERY_DESC)

        // The row with no figure must land at the bottom rather than being
        // ranked as 0%. On a device where the parser broke entirely, treating
        // null as zero would present the whole list as spotless.
        assertEquals("Mango", labels(model).last())
    }

    @Test
    fun `sorts by runtime, longest first`() = runTest {
        val model = vm(
            foreground = mapOf(
                "com.example.apple" to 3 * 3_600_000L,
                "com.example.zebra" to 60_000L,
            ),
        )
        model.load()
        model.onSortByChange(SortBy.RUNTIME_DESC)

        // Apple has the least battery and the most runtime, so this order can
        // only come from the runtime comparator - not from battery, and not
        // from the alphabet.
        assertEquals(listOf("Apple", "Zebra", "Mango"), labels(model))
    }

    @Test
    fun `a measured zero runtime still outranks an unmeasured one`() = runTest {
        val model = vm(
            foreground = mapOf("com.example.apple" to 0L),
            usageGranted = true,
        )
        model.load()
        model.onSortByChange(SortBy.RUNTIME_DESC)

        // Usage access is granted and the window was read, so every package
        // present in the list has a measured figure - zero included. Nothing
        // should be pushed to the unmeasured tail here.
        assertTrue(model.state.value.rows.all { it.metric?.foregroundMillis != null })
    }

    @Test
    fun `declining usage access costs runtime and not battery`() = runTest {
        val model = vm(usageGranted = false)
        model.load()

        assertTrue(model.state.value.needsUsageAccess)
        val rows = model.state.value.rows.associateBy { it.app.label }
        assertNull(rows.getValue("Zebra").metric?.foregroundMillis)
        assertEquals(39.8, rows.getValue("Zebra").metric?.batteryMah!!, 0.001)
    }

    @Test
    fun `states the window the numbers cover`() = runTest {
        val model = vm()
        model.load()

        val window = checkNotNull(model.state.value.metricsWindow)
        assertEquals(51 * 60_000L + 46_000L + 370L, window.millis)
        assertTrue(window.sinceLastCharge)
    }

    @Test
    fun `severity stays the default order`() = runTest {
        val model = vm()
        model.load()

        // Zebra is battery-whitelisted in policyShell(), so it earns
        // RECOMMENDED and leads under the default sort. Nothing about adding
        // two metric sorts may change what the app opens on.
        assertEquals(SortBy.SEVERITY, model.state.value.sortBy)
        assertEquals("Zebra", labels(model).first())
    }
}
