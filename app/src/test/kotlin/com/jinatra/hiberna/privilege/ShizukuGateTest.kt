package com.jinatra.hiberna.privilege

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        // CHECKING alone only proves it by inference; assert directly that no
        // platform member was ever touched.
        assertTrue(platform.observedThreads.isEmpty())
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

        // The grant happened, but nothing has re-evaluated it yet - request()
        // does not refresh. True regardless of when request()'s own IPC runs,
        // because request() never touches state itself.
        assertEquals(PrivilegeState.PERMISSION_DENIED, gate.state.value)

        platform.emitStateChanged()
        withTimeout(5_000) { gate.state.first { it == PrivilegeState.READY } }
        // request() dispatches its IPC onto scope + Dispatchers.IO rather than
        // running it synchronously (see F1); poll rather than assert
        // immediately, since that hop is asynchronous.
        withTimeout(5_000) { while (platform.requestCount == 0) delay(10) }
        assertEquals(1, platform.requestCount)
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

        // The PERMISSION_DENIED case, where request() *does* reach the
        // platform, is covered by `request performs IPC off the caller
        // thread` below - its IPC is dispatched asynchronously (F1), which
        // this test's runTest + backgroundScope dispatcher cannot await.
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun `request performs IPC off the caller thread`() = runBlocking {
        // request() must not repeat the mistake this very fix corrects: it is
        // a plain fun that used to call platform.requestPermission()
        // synchronously, which is a blocking binder transaction from
        // whatever thread called it - in practice a Compose onClick, i.e. the
        // main thread.
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = false, installed = true)
        val scope = CoroutineScope(Dispatchers.Default)
        val gate = ShizukuGate(platform, scope)
        gate.refresh()
        assertEquals(PrivilegeState.PERMISSION_DENIED, gate.state.value)

        val caller = newSingleThreadContext("fake-main")
        try {
            val callerThread = withContext(caller) {
                gate.request()
                Thread.currentThread().name
            }
            withTimeout(5_000) { while (platform.requestCount == 0) delay(10) }
            assertTrue("no IPC recorded", platform.observedThreads.isNotEmpty())
            assertFalse(
                "requestPermission ran on the caller thread: ${platform.observedThreads}",
                platform.observedThreads.contains(callerThread),
            )
        } finally {
            caller.close()
            gate.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `concurrent refreshes do not let a slow one overwrite a newer result`() = runBlocking {
        // The sticky listener firing at registration and AppContainer's own
        // explicit initial refresh routinely overlap in practice; this
        // reproduces that race deterministically. `slow` starts first, reads
        // the *old* permission value, then blocks - simulating a refresh that
        // began before a platform-state change but is still running after a
        // second, faster refresh (started after the change) has already
        // completed and written the correct, newer result. Without
        // serialising refresh(), `slow` then overwrites that correct result
        // with its own stale one.
        val fake = FakeShizukuPlatform(binderAlive = true, permissionGranted = false)
        val readStaleValue = CountDownLatch(1)
        val platform = SlowFirstCallPlatform(fake, readStaleValue)
        val scope = CoroutineScope(Dispatchers.Default)
        val gate = ShizukuGate(platform, scope)

        platform.slowNextCall = true
        val slow = scope.launch { gate.refresh() }
        assertTrue(
            "slow refresh never reached checkSelfPermission",
            readStaleValue.await(5, TimeUnit.SECONDS),
        )

        // The platform changes while `slow` is blocked mid-evaluate.
        fake.permissionGranted = true
        val fast = scope.launch { gate.refresh() }

        fast.join()
        slow.join()

        assertEquals(PrivilegeState.READY, gate.state.value)
        gate.dispose()
        scope.cancel()
    }

    @Test
    fun `a second addStateListener call replaces the first rather than throwing`() {
        // FakeShizukuPlatform must certify what the real platform actually
        // does: RealShizukuPlatform.addStateListener un-registers before
        // registering, so a fake that throws on re-registration would
        // certify device behaviour that does not exist.
        val platform = FakeShizukuPlatform()
        platform.addStateListener {}
        platform.addStateListener {}
        assertTrue(platform.hasListener)
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun `refresh's IPC happens off the caller thread`() = runBlocking {
        // Nothing pinned that refresh()'s own IPC runs off the caller
        // dispatcher: deleting withContext(Dispatchers.IO) from refresh()
        // must break this.
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true)
        val scope = CoroutineScope(Dispatchers.Default)
        val gate = ShizukuGate(platform, scope)
        val caller = newSingleThreadContext("fake-main")
        try {
            val callerThread = withContext(caller) {
                gate.refresh()
                Thread.currentThread().name
            }
            assertTrue("no IPC recorded", platform.observedThreads.isNotEmpty())
            assertFalse(
                "refresh's IPC ran on the caller thread: ${platform.observedThreads}",
                platform.observedThreads.contains(callerThread),
            )
        } finally {
            caller.close()
            gate.dispose()
            scope.cancel()
        }
    }
}

/**
 * Wraps a [FakeShizukuPlatform] so the *first* call to [checkSelfPermission]
 * captures the current value, signals [readStaleValue], and then - only on
 * that first call - sleeps before returning the value it already captured.
 * This lets a test start a slow refresh, wait until it has read a stale
 * value and is blocked, mutate the platform, and run a second, fast refresh
 * to completion, deterministically reproducing the F2 race without any
 * dependency between the two refreshes that could deadlock.
 */
private class SlowFirstCallPlatform(
    private val delegate: FakeShizukuPlatform,
    private val readStaleValue: CountDownLatch,
) : ShizukuPlatform by delegate {

    @Volatile
    var slowNextCall: Boolean = false

    override fun checkSelfPermission(): Boolean {
        val result = delegate.checkSelfPermission()
        if (slowNextCall) {
            slowNextCall = false
            readStaleValue.countDown()
            Thread.sleep(300)
        }
        return result
    }
}
