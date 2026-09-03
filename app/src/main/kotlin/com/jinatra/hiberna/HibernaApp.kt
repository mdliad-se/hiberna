package com.jinatra.hiberna

import android.app.Application
import com.jinatra.hiberna.privilege.RealShizukuPlatform
import com.jinatra.hiberna.shell.ShellBackend
import com.jinatra.hiberna.shell.ShizukuShellBackend
import com.jinatra.hiberna.shell.realShellBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HibernaApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val shell: ShellBackend = realShellBackend(this)
        assertRealBackend(shell, isDebug = BuildConfig.DEBUG)
        container = AppContainer(context = this, shell = shell, scope = appScope)
    }
}

/**
 * The failure this app cannot afford is a build that appears to restrict apps
 * while touching nothing. A release binary wired to anything but the real
 * Shizuku backend *over the real Shizuku platform* must not start.
 *
 * The class check alone is not enough: `ShizukuShellBackend` over a fake
 * platform is the correct class doing nothing at all.
 */
fun assertRealBackend(backend: ShellBackend, isDebug: Boolean) {
    if (isDebug) return
    check(backend is ShizukuShellBackend && backend.platform is RealShizukuPlatform) {
        val platform = (backend as? ShizukuShellBackend)?.platform?.let { it::class.simpleName }
        "release build wired to ${backend::class.simpleName}" +
            (platform?.let { " over $it" } ?: "") +
            "; expected ShizukuShellBackend over RealShizukuPlatform"
    }
    // A minified build that lost Shizuku.newProcess installs and launches
    // cleanly and then fails every command. Fail here instead, where it is a
    // build defect and not a user's problem.
    check(RealShizukuPlatform.isPrivilegedEntryPointResolvable) {
        "release build cannot resolve Shizuku.newProcess; the R8 keep rule for the " +
            "Shizuku SDK package is missing or stale"
    }
}
