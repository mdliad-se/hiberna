package com.jinatra.hiberna

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jinatra.hiberna.privilege.FakeShizukuPlatform
import com.jinatra.hiberna.privilege.PrivilegeState
import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShizukuShellBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppContainerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the container owns one gate over the backend own platform`() = runBlocking {
        // Task 10 must take this gate rather than build a second one: a second
        // RealShizukuPlatform means a second set of SDK listeners and a second
        // source of truth for the same binder.
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true)
        val scope = CoroutineScope(Dispatchers.Default)
        val container = AppContainer(context, ShizukuShellBackend(platform), scope)

        val gate = container.gate
        assertSame(gate, container.gate)
        assertTrue("the container gate must register the SDK listeners", platform.hasListener)

        gate.refresh()
        assertEquals(PrivilegeState.READY, gate.state.value)

        container.dispose()
        assertFalse(platform.hasListener)
        scope.cancel()
    }

    @Test
    fun `the gate evaluates once on creation without anyone calling refresh`() = runBlocking {
        // Shizuku already running at launch produces no change event, so a
        // gate that only reacts to events would show CHECKING forever.
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true)
        val scope = CoroutineScope(Dispatchers.Default)
        val container = AppContainer(context, ShizukuShellBackend(platform), scope)

        withTimeout(5_000) { container.gate.state.first { it == PrivilegeState.READY } }

        container.dispose()
        scope.cancel()
    }

    @Test
    fun `asking a non-Shizuku container for a gate fails loudly`() {
        val scope = CoroutineScope(Dispatchers.Default)
        val container = AppContainer(context, FakeShellBackend(), scope)

        val e = assertThrows(IllegalStateException::class.java) { container.gate }
        assertTrue(requireNotNull(e.message).contains("FakeShellBackend"))
        scope.cancel()
    }
}
