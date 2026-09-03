package com.jinatra.hiberna.shell

import com.jinatra.hiberna.privilege.ShizukuPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShizukuShellBackend(
    private val platform: ShizukuPlatform,
) : ShellBackend {

    override val isAvailable: Boolean
        get() = platform.isBinderAlive && platform.checkSelfPermission()

    override suspend fun exec(command: List<String>): ShellResult {
        if (!isAvailable) {
            return ShellResult(-1, "", "shizuku not available")
        }
        return withContext(Dispatchers.IO) { platform.exec(command) }
    }
}
