// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.privilege

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RealShizukuPlatformTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `isInstalled is false when the Shizuku manager package is absent`() {
        assertFalse(RealShizukuPlatform(context).isInstalled)
    }

    @Test
    fun `isInstalled is true once the Shizuku manager package exists`() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = RealShizukuPlatform.SHIZUKU_PACKAGE },
        )

        assertTrue(RealShizukuPlatform(context).isInstalled)
    }

    @Test
    fun `a dead binder never throws out of the platform`() {
        // Robolectric has no Shizuku service; every member must degrade to a
        // safe answer rather than propagate.
        val platform = RealShizukuPlatform(context)

        assertFalse(platform.isBinderAlive)
        assertFalse(platform.checkSelfPermission())
    }
}
