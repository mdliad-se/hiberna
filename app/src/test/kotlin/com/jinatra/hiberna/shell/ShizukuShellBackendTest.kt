package com.jinatra.hiberna.shell

import com.jinatra.hiberna.privilege.FakeShizukuPlatform
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuShellBackendTest {

    @Test
    fun `is unavailable when permission is missing`() {
        val backend = ShizukuShellBackend(FakeShizukuPlatform(binderAlive = true, permissionGranted = false))
        assertFalse(backend.isAvailable)
    }

    @Test
    fun `is available when binder is alive and permission granted`() {
        val backend = ShizukuShellBackend(FakeShizukuPlatform(binderAlive = true, permissionGranted = true))
        assertTrue(backend.isAvailable)
    }

    @Test
    fun `exec without privilege returns a failure instead of throwing`() = runBlocking {
        val platform = FakeShizukuPlatform(binderAlive = false)
        val backend = ShizukuShellBackend(platform)

        val result = backend.exec(listOf("cmd", "appops", "get", "a.b.c", "RUN_ANY_IN_BACKGROUND"))

        assertFalse(result.isSuccess)
        assertTrue(result.stderr.contains("not available"))
        assertEquals(ShellExit.UNAVAILABLE, result.exitCode)
        // Nothing must reach the platform when the gate is shut.
        assertEquals(emptyList<List<String>>(), platform.executed)
    }

    @Test
    fun `the exact argv list reaches the platform unaltered`() = runBlocking {
        // The whole safety story of this app is the precise command it sends:
        // one extra or reordered token is a different, possibly destructive
        // system call. Assert the argv, not just that something ran.
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true)
        platform.scriptedResult = ShellResult(0, "", "")
        val backend = ShizukuShellBackend(platform)
        val argv = listOf("cmd", "appops", "set", "com.example.app", "RUN_ANY_IN_BACKGROUND", "ignore")

        backend.exec(argv)

        assertEquals(listOf(argv), platform.executed)
        assertEquals(argv, platform.executed.single())
    }

    @Test
    fun `every executed command is recorded in order`() = runBlocking {
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true)
        val backend = ShizukuShellBackend(platform)

        backend.exec(listOf("cmd", "deviceidle", "whitelist", "+com.a"))
        backend.exec(listOf("cmd", "netpolicy", "add", "restrict-background", "10123"))

        assertEquals(
            listOf(
                listOf("cmd", "deviceidle", "whitelist", "+com.a"),
                listOf("cmd", "netpolicy", "add", "restrict-background", "10123"),
            ),
            platform.executed,
        )
    }

    @Test
    fun `exit code and stderr pass through from the platform untouched`() = runBlocking {
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true)
        platform.scriptedResult = ShellResult(137, "partial", "Security exception: uid 2000 not allowed")
        val backend = ShizukuShellBackend(platform)

        val result = backend.exec(listOf("cmd", "appops", "set", "a.b.c", "RUN_ANY_IN_BACKGROUND", "ignore"))

        assertEquals(137, result.exitCode)
        assertEquals("partial", result.stdout)
        assertEquals("Security exception: uid 2000 not allowed", result.stderr)
        assertFalse(result.isSuccess)
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun `availability IPC happens off the caller dispatcher`() = runBlocking {
        // isBinderAlive and checkSelfPermission are real binder calls. Reading
        // them before entering the IO context blocks whatever dispatcher the
        // caller is on - in practice the main thread.
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true)
        val backend = ShizukuShellBackend(platform)
        val caller = newSingleThreadContext("fake-main")
        try {
            val callerThread = withContext(caller) {
                backend.exec(listOf("cmd", "appops", "get", "a.b.c", "RUN_ANY_IN_BACKGROUND"))
                Thread.currentThread().name
            }
            assertTrue("no IPC recorded", platform.observedThreads.isNotEmpty())
            assertFalse(
                "binder IPC ran on the caller thread: ${platform.observedThreads}",
                platform.observedThreads.contains(callerThread),
            )
        } finally {
            caller.close()
        }
    }
}
