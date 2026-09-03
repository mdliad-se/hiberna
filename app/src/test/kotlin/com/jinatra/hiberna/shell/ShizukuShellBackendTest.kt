package com.jinatra.hiberna.shell

import com.jinatra.hiberna.privilege.FakeShizukuPlatform
import kotlinx.coroutines.test.runTest
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
    fun `exec without privilege returns a failure instead of throwing`() = runTest {
        val backend = ShizukuShellBackend(FakeShizukuPlatform(binderAlive = false))

        val result = backend.exec(listOf("cmd", "appops", "get", "a.b.c", "RUN_ANY_IN_BACKGROUND"))

        assertFalse(result.isSuccess)
        assertTrue(result.stderr.contains("not available"))
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun `exec delegates to the platform when privileged`() = runTest {
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true)
        platform.scriptedResult = ShellResult(0, "mode=ignore", "")
        val backend = ShizukuShellBackend(platform)

        val result = backend.exec(listOf("cmd", "appops", "get", "a.b.c", "RUN_ANY_IN_BACKGROUND"))

        assertEquals("mode=ignore", result.stdout)
    }
}
