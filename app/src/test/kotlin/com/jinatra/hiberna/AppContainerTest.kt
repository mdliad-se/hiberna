package com.jinatra.hiberna

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.privilege.FakeShizukuPlatform
import com.jinatra.hiberna.privilege.PrivilegeState
import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShellResult
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
import org.robolectric.Shadows.shadowOf

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

    // --- Task 14: the container is widened to hold everything a real screen ---
    // --- needs, not just the gate.                                          ---

    private fun installPackage(pkg: String) {
        val info = android.content.pm.ApplicationInfo().apply {
            packageName = pkg
            uid = 10_500
            nonLocalizedLabel = pkg
        }
        shadowOf(context.packageManager).installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = pkg
                applicationInfo = info
            },
        )
    }

    @Test
    fun `appListViewModel wires the container's own repositories, not a second set`() = runBlocking {
        installPackage("com.example.game")
        val shell = FakeShellBackend().apply {
            script("appops query-op", ShellResult(0, "", ""))
            script("deviceidle whitelist", ShellResult(0, "sys,com.example.game,10500", ""))
            script("netpolicy", ShellResult(0, "", ""))
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val container = AppContainer(context, shell, scope)

        val model = container.appListViewModel()
        model.load()

        assertTrue(
            "expected the installed package the container's own PackageManagerAppRepository " +
                "should have found",
            model.state.value.rows.any { it.app.packageName == "com.example.game" },
        )
        scope.cancel()
    }

    @Test
    fun `presets and overrides share the one canonical hibernaDataStore instance`() = runBlocking {
        // The regression this guards: a second `PreferenceDataStoreFactory.create(...)`
        // (or a second `preferencesDataStore` delegate) over the same file has no
        // mutual exclusion with the first and can lose writes - see
        // HibernaDataStore.kt's doc. Two separate AppContainer instances built
        // over the *same* Context must still observe each other's writes,
        // because both are required to route through `Context.hibernaDataStore`.
        val scopeA = CoroutineScope(Dispatchers.Default)
        val scopeB = CoroutineScope(Dispatchers.Default)
        val containerA = AppContainer(context, FakeShellBackend(), scopeA)
        val containerB = AppContainer(context, FakeShellBackend(), scopeB)

        containerA.overrides.setOverridden("com.example.sms", true)

        assertTrue(containerB.overrides.overridden.first().contains("com.example.sms"))

        val preset = com.jinatra.hiberna.preset.Preset(
            id = "custom", name = "Custom",
            backgroundActivity = BackgroundActivity.RESTRICTED, restrictBackgroundData = true,
        )
        containerA.presets.save(preset)
        assertTrue(containerB.presets.presets.first().any { it.id == "custom" })

        scopeA.cancel()
        scopeB.cancel()
    }
}
