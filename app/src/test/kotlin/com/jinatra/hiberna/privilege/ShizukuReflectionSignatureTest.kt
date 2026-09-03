package com.jinatra.hiberna.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.lang.reflect.Modifier

/**
 * `Shizuku.newProcess` is private: the compiler cannot check the call, so a
 * dependency bump that renames it, changes its arity, or changes its return
 * type would compile cleanly and fail on a user's phone at the moment they
 * press the one button this app has.
 *
 * This is a plain JVM test on purpose - no device, no Robolectric - so it runs
 * on every `test` invocation and breaks the build instead.
 */
class ShizukuReflectionSignatureTest {

    @Test
    fun `newProcess resolves with the exact signature RealShizukuPlatform reflects`() {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )

        assertTrue("newProcess must be static", Modifier.isStatic(method.modifiers))
        assertEquals(ShizukuRemoteProcess::class.java, method.returnType)
    }

    @Test
    fun `the returned process exposes waitForTimeout, which java lang Process does not`() {
        // java.lang.Process.waitFor(long, TimeUnit) is NOT overridden by
        // ShizukuRemoteProcess; the JDK default implementation polls
        // exitValue(), which this wrapper throws over binder. exec() must call
        // waitForTimeout instead, so that method has to exist.
        val method = ShizukuRemoteProcess::class.java.getDeclaredMethod(
            "waitForTimeout",
            java.lang.Long.TYPE,
            java.util.concurrent.TimeUnit::class.java,
        )

        assertEquals(java.lang.Boolean.TYPE, method.returnType)
        assertTrue(
            "ShizukuRemoteProcess must remain a java.lang.Process",
            Process::class.java.isAssignableFrom(ShizukuRemoteProcess::class.java),
        )
    }

    @Test
    fun `the reflected method is cached and resolvable through the production path`() {
        assertTrue(RealShizukuPlatform.isPrivilegedEntryPointResolvable)
    }
}
