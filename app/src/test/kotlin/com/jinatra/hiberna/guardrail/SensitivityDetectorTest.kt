// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.guardrail

import android.accounts.AccountManager
import android.accounts.AuthenticatorDescription
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SensitivityDetectorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `flags a package on the static fallback list`() = runTest {
        val detector = PlatformSensitivityDetector(context, staticList = setOf("com.example.sms"))

        assertEquals(Sensitivity.LIKELY_BREAKS, detector.classify("com.example.sms"))
    }

    @Test
    fun `flags a listed package but leaves an unrelated one alone`() = runTest {
        // Both assertions live in one test on purpose: an always-NONE stub
        // would still pass a test that only checked the negative case, so
        // the positive case has to sit right next to it to mean anything.
        val detector = PlatformSensitivityDetector(context, staticList = setOf("com.example.sms"))

        assertEquals(Sensitivity.LIKELY_BREAKS, detector.classify("com.example.sms"))
        assertEquals(Sensitivity.NONE, detector.classify("com.example.game"))
    }

    @Test
    fun `every default fallback entry is actually flagged through classify`() = runTest {
        // The previous version of this test only inspected the DEFAULT_SENSITIVE
        // set's string contents and never called classify - it could not have
        // caught a typo'd entry, a broken `in staticList` check, or a detector
        // that ignores its default constructor argument entirely.
        val detector = PlatformSensitivityDetector(context)

        DEFAULT_SENSITIVE.forEach { pkg ->
            assertEquals("expected $pkg to be flagged", Sensitivity.LIKELY_BREAKS, detector.classify(pkg))
        }
    }

    @Test
    fun `flags the app that resolves ACTION_SET_ALARM`() = runTest {
        val pkg = "com.example.alarmclock"
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(AlarmClock.ACTION_SET_ALARM),
            activityResolveInfo(pkg, "AlarmActivity"),
        )
        val detector = PlatformSensitivityDetector(context, staticList = emptySet())

        assertEquals(Sensitivity.LIKELY_BREAKS, detector.classify(pkg))
    }

    @Test
    fun `flags a package registered as an AccountManager authenticator`() = runTest {
        val pkg = "com.example.authenticator"
        shadowOf(AccountManager.get(context)).addAuthenticator(
            AuthenticatorDescription("example.account.type", pkg, 0, 0, 0, 0),
        )
        val detector = PlatformSensitivityDetector(context, staticList = emptySet())

        assertEquals(Sensitivity.LIKELY_BREAKS, detector.classify(pkg))
    }

    @Test
    fun `flags the resolved home launcher package`() = runTest {
        val pkg = "com.example.launcher"
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        shadowOf(context.packageManager).addResolveInfoForIntent(
            homeIntent,
            activityResolveInfo(pkg, "HomeActivity"),
        )
        val detector = PlatformSensitivityDetector(context, staticList = emptySet())

        assertEquals(Sensitivity.LIKELY_BREAKS, detector.classify(pkg))
    }

    @Test
    fun `flags an app that resolves ACTION_ASSIST`() = runTest {
        val pkg = "com.example.assistant"
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_ASSIST),
            activityResolveInfo(pkg, "AssistantActivity"),
        )
        val detector = PlatformSensitivityDetector(context, staticList = emptySet())

        assertEquals(Sensitivity.LIKELY_BREAKS, detector.classify(pkg))
    }

    @Test
    fun `flags a package declaring a qualifying foreground service type`() = runTest {
        val pkg = "com.example.navigation"
        val installed = installPackageWithForegroundServiceType(
            pkg,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        if (!installed) {
            // Documented, not silently skipped: this compileSdk's public
            // ServiceInfo surface exposes only getForegroundServiceType(),
            // no public setter (verified via javap against
            // platforms/android-36/android.jar). On this Robolectric
            // runtime, reflection into the underlying field (confirmed
            // working at the time this test was written - see
            // installPackageWithForegroundServiceType) is enough to work
            // around that, so this branch does not currently run. It exists
            // so that if a future Robolectric/AOSP layout removes or renames
            // that field, this test degrades to a documented no-op instead
            // of a mysterious failure unrelated to this detector's own code.
            return@runTest
        }
        val detector = PlatformSensitivityDetector(context, staticList = emptySet())

        assertEquals(Sensitivity.LIKELY_BREAKS, detector.classify(pkg))
    }

    @Test
    fun `does not flag a package whose only foreground service type is unrelated`() = runTest {
        val pkg = "com.example.camera"
        val installed = installPackageWithForegroundServiceType(
            pkg,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        if (!installed) return@runTest
        val detector = PlatformSensitivityDetector(context, staticList = emptySet())

        assertEquals(Sensitivity.NONE, detector.classify(pkg))
    }

    // --- the pure bitmask decision behind the check above (Task 12 ---
    // --- precondition 2): tested directly with Int literals, so this ---
    // --- coverage cannot silently stop testing anything the way the ---
    // --- reflection-based tests above can if a future Robolectric/AOSP ---
    // --- layout removes the field they poke at. ---

    @Test
    fun `qualifies a type carrying the location bit`() {
        assertTrue(hasQualifyingForegroundServiceType(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION))
    }

    @Test
    fun `qualifies a type carrying the media playback bit`() {
        assertTrue(hasQualifyingForegroundServiceType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK))
    }

    @Test
    fun `does not qualify a type carrying only an unrelated bit`() {
        assertFalse(hasQualifyingForegroundServiceType(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA))
    }

    @Test
    fun `does not qualify zero`() {
        assertFalse(hasQualifyingForegroundServiceType(0))
    }

    @Test
    fun `qualifies a combined type that also carries an unrelated bit`() {
        // A service can declare more than one type; only one qualifying bit
        // needs to be set for the whole package to be flagged.
        val combined = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        assertTrue(hasQualifyingForegroundServiceType(combined))
    }

    // --- Task 12 precondition 1: classify() must read every installed ---
    // --- package's services from one batched getInstalledPackages(GET_SERVICES) ---
    // --- call rather than a per-package getPackageInfo Binder round trip - ---
    // --- the bulk-apply path calls classify() for hundreds of packages. ---

    @Test
    fun `classifies several packages' foreground service types from one detector instance`() = runTest {
        val navPkg = "com.example.navigation"
        val cameraPkg = "com.example.camera"
        val plainPkg = "com.example.plain"
        val navInstalled = installPackageWithForegroundServiceType(
            navPkg,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        val cameraInstalled = installPackageWithForegroundServiceType(
            cameraPkg,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        if (!navInstalled || !cameraInstalled) return@runTest
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = plainPkg
                applicationInfo = ApplicationInfo().apply { packageName = plainPkg }
            },
        )
        val detector = PlatformSensitivityDetector(context, staticList = emptySet())

        // All three resolved correctly from the same underlying device-wide
        // query - a package with no services at all (plainPkg) must not
        // crash the map lookup, just report NONE.
        assertEquals(Sensitivity.LIKELY_BREAKS, detector.classify(navPkg))
        assertEquals(Sensitivity.NONE, detector.classify(cameraPkg))
        assertEquals(Sensitivity.NONE, detector.classify(plainPkg))
    }

    @Test
    fun `a throwing source degrades to the remaining sources instead of propagating`() = runTest {
        // AccountManager.get() is forced to throw. classify() must neither
        // crash nor silently report NONE for a package a *surviving* source
        // (the live SET_ALARM query) still recognizes - that is the safety
        // guarantee this whole detector rests on.
        val flaggedElsewhere = "com.example.alarmclock"
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(AlarmClock.ACTION_SET_ALARM),
            activityResolveInfo(flaggedElsewhere, "AlarmActivity"),
        )
        val throwingContext = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? {
                if (name == ACCOUNT_SERVICE) error("boom: account service unavailable")
                return super.getSystemService(name)
            }
        }
        val detector = PlatformSensitivityDetector(throwingContext, staticList = emptySet())

        assertEquals(Sensitivity.LIKELY_BREAKS, detector.classify(flaggedElsewhere))
        // F6: a package no *surviving* source flags is no longer reported as
        // the plain, all-clear NONE - a source threw, so "nothing flagged
        // it" cannot be trusted the same way a run where every source
        // actually got to look would be. Before F6 this asserted
        // Sensitivity.NONE here, which is exactly the bug the review finding
        // describes: a throwing AccountManager (or a package-heavy device's
        // TransactionTooLargeException from getInstalledPackages) made every
        // unflagged app look silently safe to restrict.
        assertEquals(Sensitivity.UNKNOWN, detector.classify("com.example.ordinary"))
    }

    @Test
    fun `every source succeeding with nothing flagged is still NONE, not UNKNOWN`() = runTest {
        // The negative control for the test above: UNKNOWN must only appear
        // when a source genuinely threw, never merely because a package was
        // not flagged by anything. Without this test, a detector that always
        // returned UNKNOWN for an unflagged package (whether or not anything
        // actually threw) would still pass the throwing-source test above.
        val detector = PlatformSensitivityDetector(context, staticList = emptySet())

        assertEquals(Sensitivity.NONE, detector.classify("com.example.ordinary"))
    }

    @Test
    fun `default list covers the categories that break loudest`() {
        listOf("clock", "messag", "auth").forEach { needle ->
            assert(DEFAULT_SENSITIVE.any { it.contains(needle) }) { "no default entry matching $needle" }
        }
    }

    private fun activityResolveInfo(pkg: String, className: String): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = className
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
    }

    /**
     * Returns false, without throwing, if this Robolectric runtime offers no
     * way - public API or reflection - to give an installed package's
     * service a non-zero foreground service type.
     */
    private fun installPackageWithForegroundServiceType(pkg: String, type: Int): Boolean {
        val service = ServiceInfo().apply {
            packageName = pkg
            name = "$pkg.TheService"
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
        val setViaReflection = runCatching {
            val setter = ServiceInfo::class.java.methods.firstOrNull { it.name == "setForegroundServiceType" }
            if (setter != null) {
                setter.invoke(service, type)
                return@runCatching true
            }
            val field = generateSequence(ServiceInfo::class.java as Class<*>) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.type == Int::class.javaPrimitiveType && it.name.contains("oregroundServiceType") }
                ?: return@runCatching false
            field.isAccessible = true
            field.setInt(service, type)
            true
        }.getOrDefault(false)
        if (!setViaReflection) return false
        if (service.foregroundServiceType != type) return false

        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
                services = arrayOf(service)
            },
        )
        return true
    }
}
