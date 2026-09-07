// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.shell

import com.jinatra.hiberna.privilege.ShizukuPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ShizukuShellBackend(
    /**
     * Exposed so the release boot assertion can look *through* the backend at
     * the platform it wraps: the right backend class over a fake platform
     * changes nothing on the device while passing a class check.
     */
    internal val platform: ShizukuPlatform,
) : ShellBackend {

    /**
     * **Two blocking binder transactions.** Never read this from the main
     * thread or from a composition; [exec] already checks it on
     * [Dispatchers.IO] for you.
     */
    override val isAvailable: Boolean
        get() = platform.isBinderAlive && platform.checkSelfPermission()

    override suspend fun exec(command: List<String>): ShellResult = withContext(Dispatchers.IO) {
        // Inside the IO context, not before it: isAvailable is binder IPC, and
        // evaluating it on the caller's dispatcher put a Shizuku round trip on
        // the main thread for every command.
        if (!isAvailable) {
            return@withContext ShellResult(ShellExit.UNAVAILABLE, "", "shizuku not available")
        }
        platform.exec(command)
    }
}
