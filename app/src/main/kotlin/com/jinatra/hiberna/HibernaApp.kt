package com.jinatra.hiberna

import android.app.Application
import com.jinatra.hiberna.privilege.RealShizukuPlatform
import com.jinatra.hiberna.shell.ShellBackend
import com.jinatra.hiberna.shell.ShizukuShellBackend

class HibernaApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val shell: ShellBackend = ShizukuShellBackend(RealShizukuPlatform())
        assertRealBackend(shell, isDebug = BuildConfig.DEBUG)
        container = AppContainer(context = this, shell = shell)
    }
}

/**
 * The failure this app cannot afford is a build that appears to restrict apps
 * while touching nothing. A release binary wired to anything but the real
 * Shizuku backend must not start.
 */
fun assertRealBackend(backend: ShellBackend, isDebug: Boolean) {
    if (isDebug) return
    check(backend is ShizukuShellBackend) {
        "release build wired to ${backend::class.simpleName}; expected ShizukuShellBackend"
    }
}
