// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jinatra.hiberna.apps.FakeAppRepository
import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.guardrail.FakeSensitivityDetector
import com.jinatra.hiberna.policy.BulkApplier
import com.jinatra.hiberna.policy.PolicyApplier
import com.jinatra.hiberna.policy.PolicyReader
import com.jinatra.hiberna.preset.DEFAULT_PRESETS
import com.jinatra.hiberna.preset.DataStoreOverrideRepository
import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShellResult
import com.jinatra.hiberna.ui.theme.JinatraTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * F1: this is the review's "dead code" finding made concrete - it drives
 * [AppListScreen] and [AppRow] through a *real* [AppListViewModel] (real
 * [BulkApplier], real [PolicyApplier], a fake shell standing in for Shizuku),
 * proving the wiring reaches all the way through, not just that the two
 * composables call whatever lambdas a test hands them directly (already
 * covered by [AppListScreenTest], [BulkBarTest], and [AppRowColorTest]).
 *
 * There is no production "route" composable yet that connects a real
 * [AppListViewModel] to [AppListScreen] - `MainActivity` still shows Task 11's
 * placeholder, and wiring it for real is explicitly Task 14's job (see the
 * task report). This test supplies the same glue Task 14 will: a small,
 * test-local `sync()` that re-reads the view model's `StateFlow`s into
 * Compose `mutableStateOf` after every dispatch, so recomposition sees the
 * new value. Suspend calls run via [runBlocking] rather than
 * `rememberCoroutineScope` - both the fake shell and the `DataStore` here are
 * synchronous/in-memory, so blocking the click callback introduces no real
 * delay, and it sidesteps needing to synchronise a separately-launched
 * coroutine with Compose's test clock.
 */
@RunWith(RobolectricTestRunner::class)
class AppListSelectionWiringTest {

    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = TemporaryFolder()

    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { File(tmp.root, "overrides.preferences_pb") })

    private fun shell() = FakeShellBackend().apply {
        script("appops query-op", ShellResult(0, "", ""))
        script("deviceidle whitelist", ShellResult(0, "user,com.example.sms,10500", ""))
        script("netpolicy list", ShellResult(0, "", ""))
        script("appops set", ShellResult(0, "", ""))
        script("deviceidle whitelist -", ShellResult(0, "", ""))
        script("netpolicy", ShellResult(0, "", ""))
    }

    private fun buildViewModel(shell: FakeShellBackend): AppListViewModel {
        val apps = FakeAppRepository(
            listOf(InstalledApp("com.example.game", "Game", 10456, isSystem = false, isEnabled = true)),
        )
        return AppListViewModel(
            apps = apps,
            reader = PolicyReader(shell),
            applier = PolicyApplier(shell, apps),
            sensitivity = FakeSensitivityDetector(emptySet()),
            bulk = BulkApplier(PolicyApplier(shell, apps)),
            overrides = DataStoreOverrideRepository(store()),
        )
    }

    @Test
    fun `long-press selects a row, shows BulkBar, and applying a preset reaches the view model`() {
        val shell = shell()
        val model = buildViewModel(shell)
        runBlocking { model.load() }

        compose.setContent {
            JinatraTheme {
                var uiState by remember { mutableStateOf(model.state.value) }
                var selected by remember { mutableStateOf(model.selected.value) }
                fun sync() {
                    uiState = model.state.value
                    selected = model.selected.value
                }

                AppListScreen(
                    state = uiState,
                    onQueryChange = {},
                    onActivityChange = { _, _ -> },
                    onRowClick = {},
                    selected = selected,
                    presets = DEFAULT_PRESETS,
                    skippedCount = model.skippedCount(DEFAULT_PRESETS.first(), emptySet()),
                    onToggleSelection = { pkg -> model.toggleSelection(pkg); sync() },
                    onApplyPreset = { preset -> runBlocking { model.applyPreset(preset) }; sync() },
                    onCancelSelection = { model.clearSelection(); sync() },
                )
            }
        }

        // BulkBar is entirely absent before anything is selected - it must
        // never collide with a plain row tap (Task 13's detail sheet).
        compose.onNodeWithText("selected", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Game").performTouchInput { longClick() }

        compose.onNodeWithText("1 selected").assertIsDisplayed()
        assertEquals(setOf("com.example.game"), model.selected.value)

        compose.onNodeWithText("Frugal").performClick()

        // The tap reached the real view model, not just a local test
        // callback: BulkApplier ran through PolicyApplier against the fake
        // shell, the selection was cleared as a side effect of a real apply,
        // and the summary was computed from a real BulkOutcome.
        assertTrue(shell.executed.any { it.joinToString(" ").contains("appops set") })
        assertEquals("Changed 1 app.", model.state.value.bulkSummary)
        assertTrue(model.selected.value.isEmpty())
        compose.onNodeWithText("1 selected").assertDoesNotExist()
    }

    @Test
    fun `cancel clears the selection and hides BulkBar`() {
        val shell = shell()
        val model = buildViewModel(shell)
        runBlocking { model.load() }

        compose.setContent {
            JinatraTheme {
                var uiState by remember { mutableStateOf(model.state.value) }
                var selected by remember { mutableStateOf(model.selected.value) }
                fun sync() {
                    uiState = model.state.value
                    selected = model.selected.value
                }

                AppListScreen(
                    state = uiState,
                    onQueryChange = {},
                    onActivityChange = { _, _ -> },
                    onRowClick = {},
                    selected = selected,
                    presets = DEFAULT_PRESETS,
                    skippedCount = 0,
                    onToggleSelection = { pkg -> model.toggleSelection(pkg); sync() },
                    onApplyPreset = { preset -> runBlocking { model.applyPreset(preset) }; sync() },
                    onCancelSelection = { model.clearSelection(); sync() },
                )
            }
        }

        compose.onNodeWithText("Game").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()

        compose.onNodeWithText("Cancel").performClick()

        assertTrue(model.selected.value.isEmpty())
        compose.onNodeWithText("1 selected").assertDoesNotExist()
        // Cancelling must never itself perform a bulk apply.
        assertTrue(shell.executed.none { it.joinToString(" ").contains("appops set") })
    }

    @Test
    fun `a normal tap toggles selection instead of opening the row while in selection mode`() {
        val shell = shell()
        val model = buildViewModel(shell)
        runBlocking { model.load() }
        var rowClicks = 0

        compose.setContent {
            JinatraTheme {
                var uiState by remember { mutableStateOf(model.state.value) }
                var selected by remember { mutableStateOf(model.selected.value) }
                fun sync() {
                    uiState = model.state.value
                    selected = model.selected.value
                }

                AppListScreen(
                    state = uiState,
                    onQueryChange = {},
                    onActivityChange = { _, _ -> },
                    onRowClick = { rowClicks++ },
                    selected = selected,
                    presets = DEFAULT_PRESETS,
                    skippedCount = 0,
                    onToggleSelection = { pkg -> model.toggleSelection(pkg); sync() },
                    onApplyPreset = { preset -> runBlocking { model.applyPreset(preset) }; sync() },
                    onCancelSelection = { model.clearSelection(); sync() },
                )
            }
        }

        // Before selection mode, a normal tap opens the row.
        compose.onNodeWithText("Game").performClick()
        assertEquals(1, rowClicks)

        // Long-press enters selection mode; a normal tap now toggles instead.
        compose.onNodeWithText("Game").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()

        compose.onNodeWithText("Game").performClick()

        assertTrue(model.selected.value.isEmpty())
        assertEquals(1, rowClicks)
    }
}
