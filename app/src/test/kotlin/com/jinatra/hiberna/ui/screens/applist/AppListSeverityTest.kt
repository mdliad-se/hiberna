// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jinatra.hiberna.apps.FakeAppRepository
import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.apps.InstalledAppRepository
import com.jinatra.hiberna.guardrail.FakeSensitivityDetector
import com.jinatra.hiberna.policy.BulkApplier
import com.jinatra.hiberna.policy.PolicyApplier
import com.jinatra.hiberna.policy.PolicyReader
import com.jinatra.hiberna.preset.DataStoreOverrideRepository
import com.jinatra.hiberna.preset.OverrideRepository
import com.jinatra.hiberna.severity.Severity
import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Task 1 of v1.1: the severity model wired into the list the app is built
 * around - see `docs/spine/specs/2026-09-07-hiberna-v1.1-design.md` section 1.
 * `AppListViewModelTest` covers the pre-existing mapping/filter/apply
 * behaviour; this file is scoped to the two things that file predates:
 * `AppRowState.severity` and the tier-first sort.
 */
class AppListSeverityTest {

    @get:Rule val tmp = TemporaryFolder()
    private var storeCounter = 0

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.root, "overrides-${storeCounter++}.preferences_pb") },
        )

    // One app per tier, deliberately inserted out of both tier order and
    // alphabetical order, so a passing sort test cannot be an accident of
    // insertion order:
    // - zebra (Recommended): battery-whitelisted, not sensitive, not system.
    // - mango (Safe): appops-restricted, not sensitive, not system.
    // - kiwi (Caution): a system app, appops-optimized, not sensitive.
    // - apple (Will break): sensitive per FakeSensitivityDetector.
    private val recommendedApp = InstalledApp("com.example.zebra", "Zebra", 10001, isSystem = false, isEnabled = true)
    private val safeApp = InstalledApp("com.example.mango", "Mango", 10002, isSystem = false, isEnabled = true)
    private val cautionApp = InstalledApp("com.example.kiwi", "Kiwi", 10003, isSystem = true, isEnabled = true)
    private val willBreakApp = InstalledApp("com.example.apple", "Apple", 10004, isSystem = false, isEnabled = true)

    // A second Recommended-tier app, alphabetically before "Zebra", to prove
    // the alphabetical tiebreak actually runs within a tier rather than the
    // sort happening to preserve insertion order.
    private val recommendedAppToo = InstalledApp("com.example.able", "Able", 10005, isSystem = false, isEnabled = true)

    private fun shell() = FakeShellBackend().apply {
        script("appops query-op", ShellResult(0, "com.example.mango", ""))
        script(
            "deviceidle whitelist",
            ShellResult(0, "user,com.example.zebra,10001\nuser,com.example.able,10005", ""),
        )
        script("netpolicy list", ShellResult(0, "", ""))
    }

    private fun vm(
        apps: List<InstalledApp>,
        sensitive: Set<String> = setOf("com.example.apple"),
        exemptingForegroundServiceType: Set<String> = emptySet(),
    ): AppListViewModel = AppListViewModel(
        apps = FakeAppRepository(apps) as InstalledAppRepository,
        reader = PolicyReader(shell()),
        applier = PolicyApplier(shell(), FakeAppRepository(apps)),
        sensitivity = FakeSensitivityDetector(sensitive, exemptingForegroundServiceType),
        bulk = BulkApplier(PolicyApplier(shell(), FakeAppRepository(apps))),
        overrides = DataStoreOverrideRepository(store()) as OverrideRepository,
    )

    @Test
    fun `each row carries the severity tier its own state actually earns`() = runTest {
        val model = vm(listOf(recommendedApp, safeApp, cautionApp, willBreakApp))
        model.load()
        model.onShowSystemChange(true)

        val bySeverity = model.state.value.rows.associate { it.app.packageName to it.severity }
        assertEquals(Severity.RECOMMENDED, bySeverity.getValue("com.example.zebra"))
        assertEquals(Severity.SAFE, bySeverity.getValue("com.example.mango"))
        assertEquals(Severity.CAUTION, bySeverity.getValue("com.example.kiwi"))
        assertEquals(Severity.WILL_BREAK, bySeverity.getValue("com.example.apple"))
    }

    @Test
    fun `H2 - a battery-whitelisted app declaring an exempting foreground service type is demoted to SAFE, not RECOMMENDED`() = runTest {
        // zebra is battery-whitelisted (see shell() above) and otherwise
        // earns RECOMMENDED (see the first test in this file) - wired here
        // through the real load() path, not severityOf directly, to prove
        // AppListViewModel actually asks the detector and threads the answer
        // into AppRowState rather than only the pure function agreeing.
        val model = vm(
            listOf(recommendedApp),
            exemptingForegroundServiceType = setOf("com.example.zebra"),
        )
        model.load()

        val row = model.state.value.rows.first { it.app.packageName == "com.example.zebra" }
        assertEquals(Severity.SAFE, row.severity)
    }

    @Test
    fun `the list sorts by tier, Recommended first, alphabetical within a tier`() = runTest {
        val model = vm(
            listOf(willBreakApp, cautionApp, safeApp, recommendedApp, recommendedAppToo),
        )
        model.load()
        model.onShowSystemChange(true)

        assertEquals(
            listOf(
                "com.example.able", // Recommended, alphabetically first
                "com.example.zebra", // Recommended, alphabetically second
                "com.example.mango", // Safe
                "com.example.kiwi", // Caution
                "com.example.apple", // Will break, last regardless of its name
            ),
            model.state.value.rows.map { it.app.packageName },
        )
    }
}
