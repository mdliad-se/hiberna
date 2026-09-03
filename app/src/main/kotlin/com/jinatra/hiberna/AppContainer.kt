package com.jinatra.hiberna

import android.content.Context
import com.jinatra.hiberna.privilege.ShizukuGate
import com.jinatra.hiberna.shell.ShellBackend
import com.jinatra.hiberna.shell.shizukuPlatformOrNull
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

    /** Releases the SDK listeners the gate registered, if it was ever built. */
    fun dispose() {
        if (gateDelegate.isInitialized()) gate.dispose()
    }
}
