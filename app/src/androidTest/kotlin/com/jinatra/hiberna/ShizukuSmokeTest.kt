// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna

import androidx.test.platform.app.InstrumentationRegistry
import com.jinatra.hiberna.apps.PackageManagerAppRepository
import com.jinatra.hiberna.policy.PolicyReader
import com.jinatra.hiberna.privilege.RealShizukuPlatform
import com.jinatra.hiberna.shell.ShizukuShellBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The only test in this project that touches real Shizuku. Every other test
 * runs under Robolectric on the JVM (`src/test`) against a fake platform -
 * this is the one thing that actually needs a paired device with Shizuku
 * running, and it must be run before any release (see the task report).
 *
 * Skipped, never failed, when Shizuku is not available on the device this
 * runs on ([assumeTrue] reports a skip rather than a failure) - a CI machine
 * or a fresh emulator with no paired Shizuku session must not report a false
 * failure here. A *skipped* result is not a substitute for actually running
 * this before a release, though: see step 4 of the task brief.
 *
 * [RealShizukuPlatform] takes the instrumentation's target context, not the
 * no-arg constructor the original task brief sketched - that constructor
 * does not exist; [com.jinatra.hiberna.shell.realShellBackend] shows the same
 * production wiring.
 */
class ShizukuSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backend = ShizukuShellBackend(RealShizukuPlatform(context))

    @Test
    fun readsRealSystemStateThroughShizuku() = runBlocking {
        assumeTrue("Shizuku not available on this device", backend.isAvailable)

        val policy = PolicyReader(backend).read().getOrThrow()

        // Every Android device ships with system packages whitelisted. An empty
        // set here means the command succeeded but returned nothing, which is
        // precisely the silent-failure mode this app must never have.
        assertTrue(
            "deviceidle whitelist came back empty - the parser or the command is wrong",
            policy.batteryWhitelisted.isNotEmpty(),
        )
    }

    @Test
    fun resolvesUidForThisAppItself() = runBlocking {
        val uid = PackageManagerAppRepository(context).uidOf("com.jinatra.hiberna")

        assertTrue("could not resolve our own uid", uid != null && uid > 0)
    }
}
