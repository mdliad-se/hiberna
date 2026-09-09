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
import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Task A of 1.1.0: filtering the list by the state each app is actually in -
 * see `docs/spine/specs/2026-09-09-state-filter-and-per-app-metrics-design.md`
 * section A. The 1.0.0 list could only be narrowed by name or by the
 * system-apps switch, so nothing answered "show me what I have already
 * restricted".
 */
class AppListFilterSortTest {

    @get:Rule val tmp = TemporaryFolder()
    private var storeCounter = 0

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(tmp.root, "overrides-${storeCounter++}.preferences_pb") },
        )

    // One app per background-activity state, plus a data-blocked one, so a
    // filter test cannot pass by accident on a list where everything shares a
    // state. Labels are deliberately out of alphabetical order relative to
    // their states.
    private val restricted = InstalledApp("com.example.mango", "Mango", 10002, isSystem = false, isEnabled = true)
    private val unrestricted = InstalledApp("com.example.zebra", "Zebra", 10001, isSystem = false, isEnabled = true)
    private val optimized = InstalledApp("com.example.apple", "Apple", 10004, isSystem = false, isEnabled = true)
    private val optimizedSystem = InstalledApp("com.android.kiwi", "Kiwi", 10003, isSystem = true, isEnabled = true)

    // mango is appops-restricted; zebra is battery-whitelisted, so
    // UNRESTRICTED; apple and kiwi are neither, so OPTIMIZED. apple's uid is
    // in the netpolicy blacklist, so it is the only data-blocked row - and it
    // is OPTIMIZED rather than RESTRICTED on purpose, because background data
    // is an independent lever and the DATA_BLOCKED filter must not be a
    // synonym for any activity state.
    private fun shell() = FakeShellBackend().apply {
        script("appops query-op", ShellResult(0, "com.example.mango", ""))
        script("deviceidle whitelist", ShellResult(0, "user,com.example.zebra,10001", ""))
        script("netpolicy list", ShellResult(0, "10004", ""))
    }

    private fun vm(
        apps: List<InstalledApp> = listOf(restricted, unrestricted, optimized, optimizedSystem),
    ): AppListViewModel = AppListViewModel(
        apps = FakeAppRepository(apps) as InstalledAppRepository,
        reader = PolicyReader(shell()),
        applier = PolicyApplier(shell(), FakeAppRepository(apps)),
        sensitivity = FakeSensitivityDetector(emptySet()),
        bulk = BulkApplier(PolicyApplier(shell(), FakeAppRepository(apps))),
        overrides = DataStoreOverrideRepository(store()) as OverrideRepository,
    )

    private fun labels(model: AppListViewModel): List<String> =
        model.state.value.rows.map { it.app.label }

    @Test
    fun `defaults to showing every state`() = runTest {
        val model = vm()
        model.load()

        assertEquals(StateFilter.ALL, model.state.value.stateFilter)
        assertEquals(setOf("Mango", "Zebra", "Apple"), labels(model).toSet())
    }

    @Test
    fun `filters to only the restricted apps`() = runTest {
        val model = vm()
        model.load()
        model.onStateFilterChange(StateFilter.RESTRICTED)

        assertEquals(listOf("Mango"), labels(model))
    }

    @Test
    fun `filters to only the unrestricted apps`() = runTest {
        val model = vm()
        model.load()
        model.onStateFilterChange(StateFilter.UNRESTRICTED)

        assertEquals(listOf("Zebra"), labels(model))
    }

    @Test
    fun `filters to only the optimized apps`() = runTest {
        val model = vm()
        model.load()
        model.onStateFilterChange(StateFilter.OPTIMIZED)

        assertEquals(listOf("Apple"), labels(model))
    }

    @Test
    fun `data-blocked is its own filter, not a synonym for restricted`() = runTest {
        val model = vm()
        model.load()
        model.onStateFilterChange(StateFilter.DATA_BLOCKED)

        // apple is OPTIMIZED for background activity and data-blocked, so a
        // DATA_BLOCKED filter that leaned on the activity state would return
        // Mango here instead.
        assertEquals(listOf("Apple"), labels(model))
    }

    @Test
    fun `the state filter composes with the system-apps switch`() = runTest {
        val model = vm()
        model.load()
        model.onStateFilterChange(StateFilter.OPTIMIZED)

        assertEquals(listOf("Apple"), labels(model))

        model.onShowSystemChange(true)
        assertEquals(setOf("Apple", "Kiwi"), labels(model).toSet())
    }

    @Test
    fun `the state filter composes with the search query`() = runTest {
        val model = vm()
        model.load()
        model.onStateFilterChange(StateFilter.OPTIMIZED)
        model.onQueryChange("zeb")

        // Zebra matches the query but is UNRESTRICTED, so the two filters
        // intersect rather than either one winning.
        assertEquals(emptyList<String>(), labels(model))
    }

    @Test
    fun `counts describe the device, not the current search`() = runTest {
        val model = vm()
        model.load()
        model.onQueryChange("mango")

        // Typing narrows the rows but must not renumber the chips: a count
        // that moves while you type cannot be read.
        assertEquals(listOf("Mango"), labels(model))
        assertEquals(3, model.state.value.stateCounts.getValue(StateFilter.ALL))
        assertEquals(1, model.state.value.stateCounts.getValue(StateFilter.RESTRICTED))
        assertEquals(1, model.state.value.stateCounts.getValue(StateFilter.UNRESTRICTED))
        assertEquals(1, model.state.value.stateCounts.getValue(StateFilter.OPTIMIZED))
        assertEquals(1, model.state.value.stateCounts.getValue(StateFilter.DATA_BLOCKED))
    }

    @Test
    fun `counts follow the system-apps switch`() = runTest {
        val model = vm()
        model.load()

        assertEquals(1, model.state.value.stateCounts.getValue(StateFilter.OPTIMIZED))

        // Kiwi is a system app and OPTIMIZED. The chips count what the list
        // can actually show, so revealing system apps must move the number.
        model.onShowSystemChange(true)
        assertEquals(2, model.state.value.stateCounts.getValue(StateFilter.OPTIMIZED))
        assertEquals(4, model.state.value.stateCounts.getValue(StateFilter.ALL))
    }

    @Test
    fun `defaults to sorting by severity`() = runTest {
        val model = vm()
        model.load()

        assertEquals(SortBy.SEVERITY, model.state.value.sortBy)
        // Zebra is battery-whitelisted, so RECOMMENDED, and leads regardless
        // of alphabetical order - the 1.0.0 behaviour, unchanged.
        assertEquals("Zebra", labels(model).first())
    }

    @Test
    fun `an empty state filter is not an error`() = runTest {
        val model = vm(listOf(unrestricted))
        model.load()
        model.onStateFilterChange(StateFilter.RESTRICTED)

        // Nothing restricted is a legitimate answer. It must stay distinct
        // from a failed read, which sets `error` - see PolicyReader's doc.
        assertEquals(emptyList<String>(), labels(model))
        assertEquals(null, model.state.value.error)
    }
}
