package com.jinatra.hiberna.privilege

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuGateTest {

    @Test
    fun `dead binder reports service not running`() = runTest {
        val platform = FakeShizukuPlatform(binderAlive = false)
        val gate = ShizukuGate(platform)

        gate.refresh()

        gate.state.test { assertEquals(PrivilegeState.SERVICE_NOT_RUNNING, awaitItem()) }
    }

    @Test
    fun `live binder without permission reports permission denied`() = runTest {
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = false)
        val gate = ShizukuGate(platform)

        gate.refresh()

        gate.state.test { assertEquals(PrivilegeState.PERMISSION_DENIED, awaitItem()) }
    }

    @Test
    fun `live binder with permission reports ready`() = runTest {
        val platform = FakeShizukuPlatform(binderAlive = true, permissionGranted = true)
        val gate = ShizukuGate(platform)

        gate.refresh()

        gate.state.test { assertEquals(PrivilegeState.READY, awaitItem()) }
    }

    @Test
    fun `request only asks the platform when the binder is alive`() = runTest {
        val dead = FakeShizukuPlatform(binderAlive = false)
        ShizukuGate(dead).request()
        assertEquals(0, dead.requestCount)

        val live = FakeShizukuPlatform(binderAlive = true, permissionGranted = false)
        ShizukuGate(live).request()
        assertEquals(1, live.requestCount)
    }
}
