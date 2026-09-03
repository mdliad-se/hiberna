package com.jinatra.hiberna.privilege

import com.jinatra.hiberna.shell.ShellResult
import rikka.shizuku.Shizuku

/**
 * Every call into the Shizuku SDK lives behind this interface. Shizuku's
 * process API is a restricted member reached by reflection; confining it here
 * keeps that reflection in one file and keeps [ShizukuGate] testable on the JVM.
 */
interface ShizukuPlatform {
    val isBinderAlive: Boolean
    fun checkSelfPermission(): Boolean
    fun requestPermission()
    fun exec(command: List<String>): ShellResult
}

class RealShizukuPlatform(
    private val requestCode: Int = 4242,
) : ShizukuPlatform {

    override val isBinderAlive: Boolean
        get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    override fun checkSelfPermission(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    override fun requestPermission() {
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    override fun exec(command: List<String>): ShellResult {
        return runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val process = method.invoke(null, command.toTypedArray(), null, null) as Process

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            ShellResult(exit, stdout.trim(), stderr.trim())
        }.getOrElse { t ->
            ShellResult(-1, "", "shizuku exec failed: ${t.message}")
        }
    }
}

class FakeShizukuPlatform(
    var binderAlive: Boolean = true,
    var permissionGranted: Boolean = true,
) : ShizukuPlatform {

    var scriptedResult: ShellResult = ShellResult(0, "", "")
    var requestCount: Int = 0
        private set
    val executed = mutableListOf<List<String>>()

    override val isBinderAlive: Boolean get() = binderAlive
    override fun checkSelfPermission(): Boolean = permissionGranted
    override fun requestPermission() { requestCount++ }
    override fun exec(command: List<String>): ShellResult {
        executed += command
        return scriptedResult
    }
}
