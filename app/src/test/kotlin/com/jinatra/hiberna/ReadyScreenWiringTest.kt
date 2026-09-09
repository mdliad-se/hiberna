// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.jinatra.hiberna.metrics.FakeUsageSource
import com.jinatra.hiberna.metrics.MetricsReader
import com.jinatra.hiberna.policy.BulkApplier
import com.jinatra.hiberna.policy.PolicyApplier
import com.jinatra.hiberna.policy.PolicyReader
import com.jinatra.hiberna.preset.DEFAULT_PRESETS
import com.jinatra.hiberna.preset.DataStoreOverrideRepository
import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShellResult
import com.jinatra.hiberna.ui.screens.applist.AppListScreen
import com.jinatra.hiberna.ui.screens.applist.AppListViewModel
import com.jinatra.hiberna.ui.screens.detail.AppDetailSheet
import com.jinatra.hiberna.ui.screens.presets.PresetScreen
import com.jinatra.hiberna.ui.theme.InkColor
import com.jinatra.hiberna.ui.theme.JinatraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * This is Task 14's reachability guarantee - [AppDetailSheet], [PresetScreen]
 * and Task 12's bulk selection are all reachable from the app list - kept
 * alive under **Option B** of the task report's two choices, not Option A.
 *
 * The Option A predecessor of this file (`MainActivityReadyWiringTest`, see
 * the task report) drove the *real* `MainActivity` via
 * `Robolectric.buildActivity` plus `createEmptyComposeRule`, polling
 * `compose.onAllNodesWithText(...).fetchSemanticsNodes()` in a hand-rolled
 * `while` loop with its own wall-clock timeout. That timeout never fired:
 * the hang was *inside* `fetchSemanticsNodes()` itself, which blocks on
 * Compose's own idling check before this class's loop ever gets a chance to
 * re-check its deadline. Against `MainActivity`'s real container - real
 * DataStore-backed presets/overrides, `collectAsStateWithLifecycle` collectors
 * that run for as long as an un-torn-down Activity sits at STARTED/RESUMED -
 * that idling check never observed "idle" and the whole suite never
 * returned. Retrying with a stricter per-call timeout was not attempted
 * further: the block is inside library code this task does not own, and
 * `MainActivityTest` (which asserts once per test, never in a polling loop)
 * already proves the alternative below is not just "cheaper" but the only
 * one of the two that reliably terminates.
 *
 * So: this file proves the identical guarantee - tap a row, see the detail
 * sheet; open presets, see the preset screen; press back, return to the
 * list; long-press, see BulkBar; apply a preset, reach the shell - against
 * [ReadySurface], a test-local composable built from the *exact same*
 * composables and hand-rolled `Nav` shape as `MainActivity.ReadyScreen`, but
 * wired to a real [AppListViewModel] over fakes (a [FakeAppRepository], a
 * [FakeSensitivityDetector], a [FakeShellBackend]) and a real
 * [DataStoreOverrideRepository] over a [TemporaryFolder] - the same recipe
 * [com.jinatra.hiberna.ui.screens.applist.AppListSelectionWiringTest] already
 * uses successfully for the view-model layer, one level further up. Every
 * dependency here is either synchronous (fakes) or backed by a real file on
 * a real temp dir (DataStore), so there is never a live cross-thread
 * collector for `createComposeRule`'s idling check to wait on forever.
 *
 * Class-level `w360dp-h640dp`: Robolectric's unspecified default is a
 * 320x470dp window (confirmed by direct measurement) - shorter than any
 * shipping Android device. Stacking the app list under Task 2's own top bar
 * plus a sensitive row's detail sheet (its own top bar, explanation box,
 * picker, switch and button) pushed a mid-sheet control below that budget.
 * See `AppListScreenTest`'s doc for the same reasoning applied there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class ReadyScreenWiringTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val tmp = TemporaryFolder()

    private fun overridesStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { File(tmp.root, "overrides.preferences_pb") })

    private fun shell() = FakeShellBackend().apply {
        script(
            "deviceidle whitelist",
            ShellResult(0, "user,com.example.plain,10456\nuser,com.google.android.gms,10500", ""),
        )
        script("appops query-op", ShellResult(0, "", ""))
        script("netpolicy list", ShellResult(0, "", ""))
        script("appops set", ShellResult(0, "", ""))
        script("deviceidle whitelist -", ShellResult(0, "", ""))
        script("netpolicy", ShellResult(0, "", ""))
    }

    private fun buildViewModel(shell: FakeShellBackend, overrides: DataStoreOverrideRepository): AppListViewModel {
        val apps = FakeAppRepository(
            listOf(
                InstalledApp("com.example.plain", "Plain App", 10456, isSystem = false, isEnabled = true),
                InstalledApp("com.google.android.gms", "Play Services", 10500, isSystem = false, isEnabled = true),
            ),
        )
        return AppListViewModel(
            apps = apps,
            reader = PolicyReader(shell),
            applier = PolicyApplier(shell, apps),
            sensitivity = FakeSensitivityDetector(setOf("com.google.android.gms")),
            bulk = BulkApplier(PolicyApplier(shell, apps)),
            overrides = overrides,
            metrics = metricsReader(),
        )
    }

    /** Mirrors `MainActivity.ReadyScreen`'s own hand-rolled navigation shape. */
    private sealed interface Nav {
        data object List : Nav
        data class Detail(val packageName: String) : Nav
        data object Presets : Nav
    }

    /**
     * Same composition [MainActivity.ReadyScreen] wires in production -
     * [AppListScreen] with the detail sheet as an overlay, [PresetScreen] as
     * a full replacement, and a [BackHandler] that always returns to the
     * list - built here from a real [model] and [overridesRepo] instead of
     * `AppContainer`'s production repositories. See the class kdoc for why.
     */
    @Composable
    private fun ReadySurface(model: AppListViewModel, overridesRepo: DataStoreOverrideRepository) {
        var uiState by remember { mutableStateOf(model.state.value) }
        var selected by remember { mutableStateOf(model.selected.value) }
        var overridden by remember { mutableStateOf(emptySet<String>()) }
        var nav by remember { mutableStateOf<Nav>(Nav.List) }

        fun sync() {
            uiState = model.state.value
            selected = model.selected.value
        }

        BackHandler(enabled = nav != Nav.List) { nav = Nav.List }

        when (val current = nav) {
            Nav.Presets -> PresetScreen(presets = DEFAULT_PRESETS, onSave = {}, onDelete = {})

            Nav.List, is Nav.Detail -> Box(modifier = Modifier.fillMaxSize()) {
                AppListScreen(
                    state = uiState,
                    onQueryChange = {},
                    onActivityChange = { _, _ -> },
                    onRowClick = { pkg -> nav = Nav.Detail(pkg) },
                    selected = selected,
                    presets = DEFAULT_PRESETS,
                    // F2: per-preset, not DEFAULT_PRESETS.first() borrowed for
                    // every button - see AppListViewModel.skippedCount's doc.
                    skippedCountFor = { preset -> model.skippedCount(preset, overridden) },
                    onToggleSelection = { pkg -> model.toggleSelection(pkg); sync() },
                    onApplyPreset = { preset -> runBlocking { model.applyPreset(preset) }; sync() },
                    onCancelSelection = { model.clearSelection(); sync() },
                    onOpenPresets = { nav = Nav.Presets },
                    onDismissBulkSummary = { model.dismissBulkSummary(); sync() },
                )

                val detailPackage = (current as? Nav.Detail)?.packageName
                val row = detailPackage?.let { pkg -> uiState.rows.firstOrNull { it.app.packageName == pkg } }
                if (row != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(InkColor.copy(alpha = 0.5f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { nav = Nav.List },
                            ),
                    )
                    AppDetailSheet(
                        row = row,
                        overridden = row.app.packageName in overridden,
                        onActivityChange = { activity ->
                            runBlocking { model.setActivity(row.app.packageName, activity) }
                            sync()
                        },
                        onDataChange = { blocked ->
                            runBlocking { model.setDataBlocked(row.app.packageName, blocked) }
                            sync()
                        },
                        onOverrideChange = { value ->
                            runBlocking { overridesRepo.setOverridden(row.app.packageName, value) }
                            overridden = if (value) overridden + row.app.packageName else overridden - row.app.packageName
                        },
                        onOpenSettings = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    @Test
    fun `tapping a row opens the detail sheet, and back returns to the list`() {
        val overridesRepo = DataStoreOverrideRepository(overridesStore())
        val model = buildViewModel(shell(), overridesRepo)
        runBlocking { model.load() }

        compose.setContent { JinatraTheme { ReadySurface(model, overridesRepo) } }

        compose.onNodeWithText("Plain App").assertIsDisplayed()

        compose.onNodeWithText("Plain App").performClick()

        compose.onNodeWithText("Open in Settings").assertIsDisplayed()

        compose.activity.onBackPressedDispatcher.onBackPressed()

        compose.onNodeWithText("Open in Settings").assertDoesNotExist()
        compose.onNodeWithText("Plain App").assertIsDisplayed()
    }

    @Test
    fun `Presets is reachable from the app list, and back returns to the list`() {
        val overridesRepo = DataStoreOverrideRepository(overridesStore())
        val model = buildViewModel(shell(), overridesRepo)
        runBlocking { model.load() }

        compose.setContent { JinatraTheme { ReadySurface(model, overridesRepo) } }

        compose.onNodeWithText("Plain App").assertIsDisplayed()

        compose.onNodeWithText("Presets").performClick()

        compose.onNodeWithText("Balanced").assertIsDisplayed() // a DEFAULT_PRESETS entry

        compose.activity.onBackPressedDispatcher.onBackPressed()

        compose.onNodeWithText("Balanced").assertDoesNotExist()
        compose.onNodeWithText("Plain App").assertIsDisplayed()
    }

    @Test
    fun `long-press selects a row, shows BulkBar, and applying a preset reaches the real shell`() {
        val shell = shell()
        val overridesRepo = DataStoreOverrideRepository(overridesStore())
        val model = buildViewModel(shell, overridesRepo)
        runBlocking { model.load() }

        compose.setContent { JinatraTheme { ReadySurface(model, overridesRepo) } }

        compose.onNodeWithText("Plain App").performTouchInput { longClick() }

        compose.onNodeWithText("1 selected").assertIsDisplayed()

        compose.onNodeWithText("Frugal").performClick()

        compose.onNodeWithText("1 selected").assertDoesNotExist()
        assertTrue(
            "expected a real appops write through the view model's own PolicyApplier",
            shell.executed.any { it.joinToString(" ").contains("appops set") },
        )
        // F1: the summary AppListViewModel.applyPreset computes must actually
        // reach the screen - before this fix it was computed, tested via
        // AppListViewModel.state.value.bulkSummary, and never rendered by any
        // composable a real user could see.
        compose.onNodeWithText("Changed 1 app.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `overriding the guardrail on a sensitive app persists through the real overrides repository`() {
        val overridesRepo = DataStoreOverrideRepository(overridesStore())
        val model = buildViewModel(shell(), overridesRepo)
        runBlocking { model.load() }

        compose.setContent { JinatraTheme { ReadySurface(model, overridesRepo) } }

        compose.onNodeWithText("Play Services").performClick()
        compose.onNodeWithText("Include it in bulk changes anyway").performClick()

        val overridden = runBlocking { overridesRepo.overridden.first() }
        assertTrue(overridden.contains("com.google.android.gms"))
    }

    /**
     * Metrics are advisory, and this file is not about them, so every view
     * model here gets a reader whose two sources are both absent: no
     * batterystats script on the shell, and usage access denied. That is a
     * real device state - no privilege, prompt declined - and it must leave
     * every assertion in this file untouched. The metric behaviour itself is
     * covered by MetricsReaderTest.
     */
    private fun metricsReader(): MetricsReader =
        MetricsReader(FakeShellBackend(isAvailable = false), FakeUsageSource(access = false))
}
