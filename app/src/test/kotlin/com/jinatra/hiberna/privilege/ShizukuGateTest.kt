package com.jinatra.hiberna.privilege

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests mutate the platform *after* the gate is constructed and assert
 * the resulting transition. Emptying [ShizukuGate.refresh] must break them.
 */
class ShizukuGateTest {

    private fun gate(platform: ShizukuPlatform, scope: CoroutineScope) = ShizukuGate(platform, scope)

    @Test
    fun `state starts at CHECKING and the constructor performs no IPC`() = runTest {
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true)
        val gate = gate(platform, backgroundScope)

        // READY is reachable from this platform, so CHECKING here proves the
        // constructor did not evaluate it.
        assertEquals(PrivilegeState.CHECKING, gate.state.value)
        gate.dispose()
    }

    @Test
    fun `refresh moves CHECKING to READY`() = runTest {
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true)
        val gate = gate(platform, backgroundScope)

        gate.refresh()

        assertEquals(PrivilegeState.READY, gate.state.value)
        gate.dispose()
    }

    @Test
    fun `service starting transitions to PERMISSION_DENIED then READY`() = runTest {
        val platform = FakeShizukuPlatform(binderAlive = false, permissionGranted = false, installed = true)
        val gate = gate(platform, backgroundScope)

        gate.refresh()
        assertEquals(PrivilegeState.SERVICE_NOT_RUNNING, gate.state.value)

        platform.binderAlive = true
        gate.refresh()
        assertEquals(PrivilegeState.PERMISSION_DENIED, gate.state.value)

        platform.permissionGranted = true
        gate.refresh()
        assertEquals(PrivilegeState.READY, gate.state.value)

        gate.dispose()
    }

    @Test
    fun `losing the service transitions READY back to SERVICE_NOT_RUNNING`() = runTest {
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true, installed = true)
        val gate = gate(platform, backgroundScope)
        gate.refresh()
        assertEquals(PrivilegeState.READY, gate.state.value)

        platform.binderAlive = false
        gate.refresh()

        assertEquals(PrivilegeState.SERVICE_NOT_RUNNING, gate.state.value)
        gate.dispose()
    }

    @Test
    fun `dead binder and no package reports SHIZUKU_ABSENT`() = runTest {
        val platform = FakeShizukuPlatform(binderAlive = false, permissionGranted = false, installed = false)
        val gate = gate(platform, backgroundScope)

        gate.refresh()

        assertEquals(PrivilegeState.SHIZUKU_ABSENT, gate.state.value)
        gate.dispose()
    }

    @Test
    fun `Sui user with a live binder and no Shizuku package is never told to install it`() = runTest {
        // Sui is the root-side implementation: no moe.shizuku.privileged.api
        // package exists, but the binder is live. Testing isInstalled alone
        // would tell these users to install something they do not need.
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true, installed = false)
        val gate = gate(platform, backgroundScope)

        gate.refresh()

        assertEquals(PrivilegeState.READY, gate.state.value)
        gate.dispose()
    }

    @Test
    fun `Sui user without permission reports PERMISSION_DENIED not SHIZUKU_ABSENT`() = runTest {
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = false, installed = false)
        val gate = gate(platform, backgroundScope)

        gate.refresh()

        assertEquals(PrivilegeState.PERMISSION_DENIED, gate.state.value)
        gate.dispose()
    }

    @Test
    fun `a platform state event refreshes the gate without an explicit call`() = runBlocking {
        // The flow this guards: open the app, see "not running", start Shizuku,
        // come back. Without a listener the gate never reaches READY.
        val platform = FakeShizukuPlatform(binderAlive = false, permissionGranted = false, installed = true)
        val scope = CoroutineScope(Dispatchers.Default)
        val gate = ShizukuGate(platform, scope)
        gate.refresh()
        assertEquals(PrivilegeState.SERVICE_NOT_RUNNING, gate.state.value)

        platform.binderAlive = true
        platform.permissionGranted = true
        platform.emitStateChanged()

        withTimeout(5_000) { gate.state.first { it == PrivilegeState.READY } }
        gate.dispose()
        scope.cancel()
    }

    @Test
    fun `the gate registers a listener on init and drops it on dispose`() = runTest {
        val platform = FakeShizukuPlatform()
        val gate = gate(platform, backgroundScope)

        assertTrue(platform.hasListener)

        gate.dispose()

        assertFalse(platform.hasListener)
    }

    @Test
    fun `request does not refresh synchronously - the listener delivers the result`() = runBlocking {
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = false, installed = true)
        val scope = CoroutineScope(Dispatchers.Default)
        val gate = ShizukuGate(platform, scope)
        gate.refresh()
        assertEquals(PrivilegeState.PERMISSION_DENIED, gate.state.value)

        platform.permissionGranted = true
        gate.request()

        assertEquals(1, platform.requestCount)
        // The grant happened, but nothing has re-evaluated it yet.
        assertEquals(PrivilegeState.PERMISSION_DENIED, gate.state.value)

        platform.emitStateChanged()
        withTimeout(5_000) { gate.state.first { it == PrivilegeState.READY } }
        gate.dispose()
        scope.cancel()
    }

    @Test
    fun `request is refused unless the last known state was PERMISSION_DENIED`() = runTest {
        // Shizuku throws inside the SDK when asked for permission with a dead
        // binder. The gate answers from cached state rather than making a
        // blocking binder call on the caller thread.
        val absent = FakeShizukuPlatform(binderAlive = false, installed = false)
        val absentGate = gate(absent, backgroundScope)
        absentGate.refresh()
        absentGate.request()
        assertEquals(0, absent.requestCount)
        absentGate.dispose()

        val notRunning = FakeShizukuPlatform(binderAlive = false, installed = true)
        val notRunningGate = gate(notRunning, backgroundScope)
        notRunningGate.refresh()
        notRunningGate.request()
        assertEquals(0, notRunning.requestCount)
        notRunningGate.dispose()

        val checking = FakeShizukuPlatform(binderAlive = true, permissionGranted = false)
        val checkingGate = gate(checking, backgroundScope)
        checkingGate.request()
        assertEquals(0, checking.requestCount)
        checkingGate.dispose()

        val denied = FakeShizukuPlatform(binderAlive = true, permissionGranted = false)
        val deniedGate = gate(denied, backgroundScope)
        deniedGate.refresh()
        deniedGate.request()
        assertEquals(1, denied.requestCount)
        deniedGate.dispose()
    }
}
