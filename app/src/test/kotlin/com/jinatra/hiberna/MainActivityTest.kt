// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.jinatra.hiberna.privilege.FakeShizukuPlatform
import com.jinatra.hiberna.privilege.PrivilegeState
import com.jinatra.hiberna.shell.ShizukuShellBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * `MainActivity` carries this app's most common flow - open the app, leave to
 * start Shizuku, come back - and, before this file, had no test of its own:
 * `GateScreenTest` only ever calls [GateScreen][com.jinatra.hiberna.ui.screens.gate.GateScreen]
 * directly, so it cannot catch a dropped `onResume` override, a `when`
 * inverted to route READY through the gate, or the refresh call moved to
 * `onPause`. All three would compile clean and ship silently.
 *
 * Uses [Robolectric.buildActivity] directly, per the review finding, rather
 * than a Compose `createAndroidComposeRule<MainActivity>()` rule - that rule
 * launches the activity itself, before a test gets a chance to install the
 * container the activity should read. [createEmptyComposeRule] instead gives
 * this test the Compose semantics tree of whatever activity is on screen,
 * without owning that activity's lifecycle.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app: HibernaApp get() = ApplicationProvider.getApplicationContext()
    private val scopes = mutableListOf<CoroutineScope>()
    private val controllers = mutableListOf<ActivityController<MainActivity>>()

    /**
     * Every [Robolectric.buildActivity] built here MUST be torn down, not
     * just have its [CoroutineScope]s cancelled: `ReadyScreen`'s
     * `LaunchedEffect`/`collectAsStateWithLifecycle` collectors keep running
     * as long as the Activity's own lifecycle sits at STARTED/RESUMED, which
     * it does forever unless something drives it through
     * `pause()`/`stop()`/`destroy()`. A test that never does that leaves a
     * live Compose root behind; `createEmptyComposeRule`'s idling check waits
     * for *every* registered root to settle, so each undestroyed root from an
     * earlier test accumulates and can eventually make a *later*, unrelated
     * test's `performClick()`/assertion spin past its own timeout - the
     * `androidx.test.espresso.AppNotIdleException` a previous run of this
     * suite hit, diagnosed by bisecting a from-scratch reproduction until
     * only "activity never torn down between tests" remained. See the task
     * report for the fuller writeup.
     */
    private fun buildAndTrack(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).also { controllers += it }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        scopes.forEach { it.cancel() }
    }

    /**
     * Replaces [HibernaApp.container] with one wired over [platform], and
     * settles its gate to whatever [platform] currently reports *before*
     * returning - entirely off the main dispatcher, matching
     * `AppContainerTest`'s own pattern - so the activity under test never has
     * to race the container's fire-and-forget initial refresh (see
     * `AppContainer.gate`'s kdoc).
     */
    private fun installContainer(platform: FakeShizukuPlatform): AppContainer {
        val scope = CoroutineScope(Dispatchers.Default)
        scopes += scope
        val container = AppContainer(app, ShizukuShellBackend(platform), scope)
        runBlocking { container.gate.refresh() }
        app.container = container
        return container
    }

    /**
     * `MainActivity.onResume`'s `lifecycleScope.launch { gate.refresh() }`
     * dispatches through `Dispatchers.Main.immediate` and then hops onto
     * `Dispatchers.IO` inside `ShizukuGate.refresh` - a real background-thread
     * hop that Robolectric's paused main looper does not drive on its own.
     *
     * Tried first: asserting immediately after `controller.resume()`, and
     * `compose.waitForIdle()` alone - both read the pre-refresh state, since
     * neither drives the IO-thread hop or the looper message that delivers
     * its result back to the main thread. This polls real wall-clock time,
     * idling the main looper on every iteration, which is the only approach
     * that observed the transition without adding a test-only dispatcher seam
     * to `ShizukuGate` itself - out of bounds here per the brief.
     */
    private fun awaitState(container: AppContainer, expected: PrivilegeState, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (container.gate.state.value != expected) {
            check(System.currentTimeMillis() < deadline) {
                "gate never reached $expected, stuck at ${container.gate.state.value}"
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `a non-READY state shows the gate screen`() {
        installContainer(FakeShizukuPlatform(binderAlive = false, installed = false, permissionGranted = false))

        buildAndTrack().setup()

        // Task 14 replaced Task 10's placeholder with the real app list;
        // "Search apps" (AppListScreen's own search field) is now the READY
        // signal a non-READY state must never show.
        compose.onNodeWithText("Search apps").assertDoesNotExist()
    }

    @Test
    fun `READY does not show the gate screen`() {
        installContainer(FakeShizukuPlatform(binderAlive = true, installed = true, permissionGranted = true))

        buildAndTrack().setup()

        compose.onNodeWithText("Search apps").assertIsDisplayed()
    }

    @Test
    fun `resuming the activity refreshes the gate`() {
        val platform = FakeShizukuPlatform(binderAlive = false, installed = true, permissionGranted = false)
        val container = installContainer(platform)

        val controller = buildAndTrack().setup()
        compose.onNodeWithText("Search apps").assertDoesNotExist()

        // The user starts Shizuku and grants access while hiberna is
        // backgrounded. The SDK's own listener only covers changes made while
        // hiberna is in the foreground, so nothing but MainActivity.onResume's
        // own refresh call can ever pick this up.
        platform.binderAlive = true
        platform.permissionGranted = true
        controller.pause()
        controller.resume()

        awaitState(container, PrivilegeState.READY)
        compose.waitForIdle()
        compose.onNodeWithText("Search apps").assertIsDisplayed()
    }
}
