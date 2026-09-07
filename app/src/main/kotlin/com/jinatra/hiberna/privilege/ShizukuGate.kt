// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.privilege

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single source of truth for whether this app can currently run a privileged
 * command, and for what the user has to do if it cannot.
 *
 * Two properties matter more than the state machine itself:
 *
 * 1. **No IPC in the constructor.** Every input to [evaluate] is a blocking
 *    binder or PackageManager call, and the constructor runs wherever the
 *    caller happens to be - in practice the main thread. [state] therefore
 *    starts at [PrivilegeState.CHECKING] and only [refresh], which is
 *    `suspend` and hops to [Dispatchers.IO], ever evaluates anything.
 * 2. **The SDK drives it.** Shizuku's binder can appear and disappear while
 *    the app is in the foreground; the common flow is "open app, see not
 *    running, start Shizuku, come back". Polling from the UI would miss that,
 *    so the gate subscribes to the SDK's own events on construction.
 *
 * [dispose] must be called or the SDK holds these listeners - and therefore
 * this object and its scope - for the life of the process.
 */
internal class ShizukuGate(
    private val platform: ShizukuPlatform,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(PrivilegeState.CHECKING)
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    /**
     * Guards [refresh] so overlapping calls run one at a time. The sticky
     * listener firing at registration and the container's own explicit
     * initial refresh routinely overlap; without this, a slow refresh that
     * started first can still finish last and overwrite a newer result with
     * a stale one. Serialising means whichever refresh runs last always reads
     * platform state as of when it actually runs, so it is never stale.
     */
    private val refreshMutex = Mutex()

    init {
        platform.addStateListener { scope.launch { refresh() } }
    }

    /** Re-evaluates privilege. All IPC happens on [Dispatchers.IO]. */
    suspend fun refresh() {
        refreshMutex.withLock {
            _state.value = withContext(Dispatchers.IO) { evaluate() }
        }
    }

    /**
     * Shows the Shizuku permission dialog.
     *
     * Deliberately does not refresh: the answer is asynchronous and arrives
     * through the SDK's permission-result listener, which drives [refresh]
     * itself. Refreshing here would just re-read a stale value.
     *
     * Answered from the last known [state] rather than by asking the platform,
     * for two reasons: asking would mean blocking binder IPC on the caller's
     * thread, and `Shizuku.requestPermission` throws when the binder is dead.
     *
     * Non-suspend so it can be called directly from a Compose `onClick`, which
     * runs on the main thread - so the actual platform call is dispatched onto
     * [scope] and hopped to [Dispatchers.IO] rather than invoked here, matching
     * the threading contract documented on [ShizukuPlatform].
     *
     * [ShizukuPlatform.requestPermission] documents that it throws inside the
     * SDK when the binder is dead. [scope] carries no
     * `CoroutineExceptionHandler`, so an uncaught failure here would reach the
     * thread's default handler as an unattributed crash, disconnected from the
     * tap that caused it. Catching it here keeps that guarantee local to this
     * class rather than depending on every [ShizukuPlatform] implementation
     * happening to swallow its own failures.
     */
    fun request() {
        if (_state.value != PrivilegeState.PERMISSION_DENIED) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { platform.requestPermission() }
            }.onFailure { t ->
                logError("requestPermission failed", t)
                // The request did not go through; re-evaluate so the gate
                // reflects reality instead of sitting on stale state.
                refresh()
            }
        }
    }

    fun dispose() {
        platform.removeStateListener()
    }

    /**
     * `android.util.Log` is a throwing stub under plain JVM unit tests, so
     * logging must never be the thing that fails [request].
     */
    private fun logError(message: String, t: Throwable) {
        try {
            Log.e(TAG, message, t)
        } catch (_: Throwable) {
            // Deliberately ignored: diagnostics are not worth a crash.
        }
    }

    private fun evaluate(): PrivilegeState = when {
        // Sui (root) users have no Shizuku package but a live binder. Testing
        // isInstalled alone would tell them to install something they do not
        // need, so absence requires both to be false.
        !platform.isBinderAlive && !platform.isInstalled -> PrivilegeState.SHIZUKU_ABSENT
        !platform.isBinderAlive -> PrivilegeState.SERVICE_NOT_RUNNING
        !platform.checkSelfPermission() -> PrivilegeState.PERMISSION_DENIED
        else -> PrivilegeState.READY
    }

    private companion object {
        private const val TAG = "HibernaShizuku"
    }
}
