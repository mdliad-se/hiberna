// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jinatra.hiberna.privilege.PrivilegeState
import com.jinatra.hiberna.privilege.RealShizukuPlatform
import com.jinatra.hiberna.ui.screens.applist.AppListScreen
import com.jinatra.hiberna.ui.screens.detail.AppDetailSheet
import com.jinatra.hiberna.ui.screens.gate.GateScreen
import com.jinatra.hiberna.ui.screens.presets.PresetScreen
import com.jinatra.hiberna.ui.theme.InkColor
import com.jinatra.hiberna.ui.theme.JinatraTheme
import kotlinx.coroutines.launch

/**
 * Where F-Droid (or the browser it hands the intent to) lists the Shizuku
 * manager app. Built from [RealShizukuPlatform.SHIZUKU_PACKAGE] - the one
 * already-defined package id - rather than a second literal copy of it here.
 */
internal val SHIZUKU_FDROID_URL =
    "https://f-droid.org/packages/${RealShizukuPlatform.SHIZUKU_PACKAGE}/"

/**
 * The real entry point Task 2 stubbed out. It shows [GateScreen] for every
 * [PrivilegeState] except [PrivilegeState.READY], which (Task 14) now shows
 * the real app list and everything reachable from it - the detail sheet
 * (Task 13) and the preset screen (Task 12's preset editor), both of which
 * existed as complete, fully tested composables with no way for a user to
 * ever reach them before this task. See [ReadyScreen] for the wiring and the
 * task report for the navigation and back-button reasoning.
 *
 * The one [com.jinatra.hiberna.privilege.ShizukuGate] for the process lives on
 * [AppContainer] (see its kdoc); this activity only ever reads it, never
 * constructs one, so there is exactly one set of Shizuku listeners no matter
 * how many times this activity is recreated.
 */
class MainActivity : ComponentActivity() {

    private val container get() = (application as HibernaApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JinatraTheme {
                val gate = container.gate
                val state by gate.state.collectAsStateWithLifecycle()
                when (state) {
                    PrivilegeState.READY -> ReadyScreen()
                    else -> GateScreen(
                        state = state,
                        onRequest = gate::request,
                        onRefresh = { lifecycleScope.launch { gate.refresh() } },
                        onInstall = ::openShizukuInstallPage,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have started Shizuku, or granted/revoked access, while
        // hiberna was backgrounded - the SDK's own listener covers changes
        // that happen while foregrounded, not this gap.
        lifecycleScope.launch { container.gate.refresh() }
    }

    /**
     * Everything behind [PrivilegeState.READY]: the app list, Task 12's
     * multi-select bulk apply, Task 13's detail sheet, and Task 12's preset
     * screen - wired together with hand-rolled navigation. See the task
     * report for why no navigation library was added for four screens.
     *
     * [nav] is a plain `remember`ed value, not `rememberSaveable`: it is pure
     * navigation *position*, not data - every screen it points at re-derives
     * its content from [AppListViewModel]/the repositories on every
     * recomposition, so losing it across a process death or a config change
     * costs the user nothing worse than landing back on the list, which is
     * where they started anyway. See the task report for the fuller
     * reasoning.
     *
     * The detail sheet renders as an overlay above [AppListScreen] rather
     * than replacing it, per the brief - a translucent scrim behind it both
     * dims the list and gives a second way to dismiss (tapping outside it),
     * alongside the system back gesture [BackHandler] below wires. The
     * preset screen, by contrast, replaces the list outright: it is one of
     * this app's four top-level surfaces (gate / list / detail / presets),
     * not something that presents over another.
     *
     * **The back button (judgement call - see the task report for the fuller
     * reasoning):** with hand-rolled navigation, Android's back gesture would
     * otherwise close the whole app from the detail sheet or the preset
     * screen - there is no `NavController` here to intercept it for free.
     * [BackHandler] is enabled for exactly as long as [nav] is not
     * [Nav.List], and always returns to it; it is never enabled while already
     * on the list, so the system's own "exit the app" behaviour survives
     * completely unchanged at the one place a user actually expects it.
     */
    @Composable
    private fun ReadyScreen() {
        val model = remember { container.appListViewModel() }
        val listState by model.state.collectAsStateWithLifecycle()
        val selected by model.selected.collectAsStateWithLifecycle()
        val presets by container.presets.presets.collectAsStateWithLifecycle(initialValue = emptyList())
        val overridden by container.overrides.overridden.collectAsStateWithLifecycle(initialValue = emptySet())
        var nav by remember { mutableStateOf<Nav>(Nav.List) }

        LaunchedEffect(Unit) { model.load() }

        BackHandler(enabled = nav != Nav.List) { nav = Nav.List }

        when (nav) {
            Nav.Presets -> PresetScreen(
                presets = presets,
                onSave = { preset -> lifecycleScope.launch { container.presets.save(preset) } },
                onDelete = { id -> lifecycleScope.launch { container.presets.delete(id) } },
            )

            Nav.List, is Nav.Detail -> Box(modifier = Modifier.fillMaxSize()) {
                AppListScreen(
                    state = listState,
                    onQueryChange = model::onQueryChange,
                    onActivityChange = { pkg, activity ->
                        lifecycleScope.launch { model.setActivity(pkg, activity) }
                    },
                    onRowClick = { pkg -> nav = Nav.Detail(pkg) },
                    selected = selected,
                    presets = presets,
                    // F2 fix: each preset's own button previews its own
                    // guardrail skip count, not one number borrowed from
                    // presets.first() - PresetScreen lets skipSensitive be
                    // toggled per preset, so a shared count could describe
                    // one preset's guardrail while a different preset's
                    // button is the one actually tapped.
                    skippedCountFor = { preset -> model.skippedCount(preset, overridden) },
                    onToggleSelection = model::toggleSelection,
                    onApplyPreset = { preset -> lifecycleScope.launch { model.applyPreset(preset) } },
                    onCancelSelection = model::clearSelection,
                    onOpenPresets = { nav = Nav.Presets },
                    onDismissBulkSummary = model::dismissBulkSummary,
                )

                val detailPackage = (nav as? Nav.Detail)?.packageName
                val row = detailPackage?.let { pkg -> listState.rows.firstOrNull { it.app.packageName == pkg } }
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
                            lifecycleScope.launch { model.setActivity(row.app.packageName, activity) }
                        },
                        onDataChange = { blocked ->
                            lifecycleScope.launch { model.setDataBlocked(row.app.packageName, blocked) }
                        },
                        onOverrideChange = { value ->
                            lifecycleScope.launch { container.overrides.setOverridden(row.app.packageName, value) }
                        },
                        onOpenSettings = { openAppSettings(row.app.packageName) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    /**
     * Sends the user to Shizuku's F-Droid listing. Needs no permission of its
     * own: `ACTION_VIEW` hands the networking to whatever app - an installed
     * F-Droid client, or the browser - registers for the URL, so this app
     * never has to (and per `ManifestPermissionsTest`, never may) declare
     * INTERNET itself.
     *
     * A device with neither an F-Droid client nor a browser cannot handle
     * this intent; [ActivityNotFoundException] is caught so that leaves the
     * user back on the gate screen rather than crashing the app.
     */
    private fun openShizukuInstallPage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_FDROID_URL))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity can handle $SHIZUKU_FDROID_URL", e)
        }
    }

    /**
     * Opens Android's own per-app settings page for [packageName] - a plain,
     * unprivileged Intent that must never go through `ShellBackend`/Shizuku
     * (see [AppDetailSheet]'s kdoc: only `shell`/`privilege` may touch either,
     * and this is neither). Follows [openShizukuInstallPage]'s exact shape:
     * this Activity owns the Intent, and [ActivityNotFoundException] is
     * caught so a device with nothing registered for this action does not
     * crash.
     */
    private fun openAppSettings(packageName: String) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity can handle app settings for $packageName", e)
        }
    }

    /** Hand-rolled navigation state for [ReadyScreen] - see its kdoc. */
    private sealed interface Nav {
        data object List : Nav
        data class Detail(val packageName: String) : Nav
        data object Presets : Nav
    }

    private companion object {
        private const val TAG = "HibernaGate"
    }
}
