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
    fun `resolves uid fresh for an installed package`() = runTest {
        install("com.example.user", "User App", 10456, system = false)

        assertEquals(10456, PackageManagerAppRepository(context).uidOf("com.example.user"))
    }

    @Test
    fun `returns null uid for an uninstalled package`() = runTest {
        assertNull(PackageManagerAppRepository(context).uidOf("com.not.installed"))
    }

    @Test
    fun `excludes apps the user has disabled from the list`() = runTest {
        // A disabled app cannot run in the foreground or the background, so a
        // background-restriction toggle for it has no observable effect. It
        // would be a confusing, dead entry in the list. Policy is still keyed
        // by package name, so nothing is lost: if the app is re-enabled later
        // it reappears here and uidOf still resolves it on demand.
        install("com.example.user", "User App", 10456, system = false, enabled = true)
        install("com.example.disabled", "Disabled App", 10789, system = false, enabled = false)

        val apps = PackageManagerAppRepository(context).load()

        assertTrue(apps.any { it.packageName == "com.example.user" })
        assertFalse(apps.any { it.packageName == "com.example.disabled" })
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
