// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.apps

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class InstalledAppRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun install(
        pkg: String,
        label: String,
        uid: Int,
        system: Boolean,
        enabled: Boolean = true,
    ) {
        val info = ApplicationInfo().apply {
            packageName = pkg
            this.uid = uid
            flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            nonLocalizedLabel = label
            this.enabled = enabled
        }
        shadowOf(context.packageManager).installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = pkg
                applicationInfo = info
            }
        )
    }

    @Test
    fun `loads installed packages with label uid and system flag`() = runTest {
        install("com.example.user", "User App", 10456, system = false)
        install("com.android.systemui", "System UI", 10023, system = true)

        val apps = PackageManagerAppRepository(context).load()

        val user = apps.first { it.packageName == "com.example.user" }
        assertEquals("User App", user.label)
        assertEquals(10456, user.uid)
        assertTrue(!user.isSystem)
        assertTrue(apps.first { it.packageName == "com.android.systemui" }.isSystem)
    }

    @Test
    fun `resolves uid fresh for an installed package, and null for an uninstalled one`() = runTest {
        install("com.example.user", "User App", 10456, system = false)

        val repository = PackageManagerAppRepository(context)

        assertEquals(10456, repository.uidOf("com.example.user"))
        assertNull(repository.uidOf("com.not.installed"))
    }

    @Test
    fun `includes disabled apps in the list, with isEnabled false`() = runTest {
        // load() must report the truth: a disabled app's stored policy (keyed by
        // package name) still exists, and hiding the row would hide that state
        // from the user. `enabled` also conflates user-disabled, admin-disabled
        // and DISABLED_UNTIL_USED, so the repository cannot and does not try to
        // infer intent - it just reports the flag.
        install("com.example.user", "User App", 10456, system = false, enabled = true)
        install("com.example.disabled", "Disabled App", 10789, system = false, enabled = false)

        val apps = PackageManagerAppRepository(context).load()

        val enabledApp = apps.first { it.packageName == "com.example.user" }
        val disabledApp = apps.first { it.packageName == "com.example.disabled" }
        assertTrue(enabledApp.isEnabled)
        assertFalse(disabledApp.isEnabled)
    }

    @Test
    fun `still resolves uid for a disabled package`() = runTest {
        // uidOf is used at apply time, independent of what load() chooses to
        // display - a policy already stored for a package that later got
        // disabled must still resolve correctly if it becomes enabled again.
        install("com.example.disabled", "Disabled App", 10789, system = false, enabled = false)

        assertEquals(10789, PackageManagerAppRepository(context).uidOf("com.example.disabled"))
    }
}
