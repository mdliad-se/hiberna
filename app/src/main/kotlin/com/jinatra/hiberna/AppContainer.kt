package com.jinatra.hiberna

import android.content.Context
import com.jinatra.hiberna.apps.InstalledAppRepository
import com.jinatra.hiberna.apps.PackageManagerAppRepository
import com.jinatra.hiberna.guardrail.PlatformSensitivityDetector
import com.jinatra.hiberna.guardrail.SensitivityDetector
import com.jinatra.hiberna.policy.BulkApplier
import com.jinatra.hiberna.policy.PolicyApplier
import com.jinatra.hiberna.policy.PolicyReader
import com.jinatra.hiberna.preset.DataStoreOverrideRepository
import com.jinatra.hiberna.preset.DataStorePresetRepository
import com.jinatra.hiberna.preset.OverrideRepository
import com.jinatra.hiberna.preset.PresetRepository
import com.jinatra.hiberna.preset.hibernaDataStore
import com.jinatra.hiberna.privilege.ShizukuGate
import com.jinatra.hiberna.shell.ShellBackend
import com.jinatra.hiberna.shell.shizukuPlatformOrNull
import com.jinatra.hiberna.ui.screens.applist.AppListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Manual dependency container. Hilt was considered and rejected: one module,
 * few dependencies, and Compose's `viewModel { }` factory covers the only
 * awkward case. Revisit if the module count grows.
 */
class AppContainer(
    val context: Context,
    val shell: ShellBackend,
    private val scope: CoroutineScope,
) {

    private val gateDelegate = lazy {
        val platform = shell.shizukuPlatformOrNull()
            ?: error(
                "AppContainer.gate needs a Shizuku-backed ShellBackend, got " +
                    "${shell::class.simpleName}",
            )
        ShizukuGate(platform, scope).also { gate ->
            // One evaluation up front. The SDK's events only report *changes*,
            // so without this a device where Shizuku is already running would
            // show CHECKING until something else happened to it.
            scope.launch { gate.refresh() }
        }
    }

    /**
     * The one privilege gate for the process. It reuses the platform [shell]
     * already holds, so there is exactly one set of Shizuku listeners and one
     * answer to "can we run commands" no matter how many screens ask.
     *
     * Task 10 takes this instance. Constructing a second [ShizukuGate] over a
     * second `RealShizukuPlatform` would double the listener registrations and
     * let two views disagree about the same binder.
     *
     * Lazy so test wiring that never asks for privilege can still use a fake
     * shell backend, while asking for a gate over one fails loudly.
     */
    internal val gate: ShizukuGate by gateDelegate

    val apps: InstalledAppRepository by lazy { PackageManagerAppRepository(context) }
    val reader: PolicyReader by lazy { PolicyReader(shell) }
    val applier: PolicyApplier by lazy { PolicyApplier(shell, apps) }
    val bulk: BulkApplier by lazy { BulkApplier(applier) }
    val sensitivity: SensitivityDetector by lazy { PlatformSensitivityDetector(context) }

    /**
     * Both built over the exact same [Context.hibernaDataStore] instance -
     * never a second `PreferenceDataStoreFactory.create(...)` (or a second
     * `preferencesDataStore` delegate) over the same file, which would have
     * no mutual exclusion with this one and could silently lose writes - see
     * that delegate's own doc. [AppContainerTest] guards this directly:
     * two [AppContainer]s built over the same [Context] must observe each
     * other's writes through these two properties.
     */
    val presets: PresetRepository by lazy { DataStorePresetRepository(context.hibernaDataStore) }
    val overrides: OverrideRepository by lazy { DataStoreOverrideRepository(context.hibernaDataStore) }

    /**
     * Builds the app list's view model wired to this container's own
     * repositories - never a second, differently-configured set of them, so
     * every screen agrees on the same reader/applier/overrides this container
     * already holds.
     */
    fun appListViewModel(): AppListViewModel = AppListViewModel(
        apps = apps,
        reader = reader,
        applier = applier,
        sensitivity = sensitivity,
        bulk = bulk,
        overrides = overrides,
    )

    /** Releases the SDK listeners the gate registered, if it was ever built. */
    fun dispose() {
        if (gateDelegate.isInitialized()) gate.dispose()
    }
}
