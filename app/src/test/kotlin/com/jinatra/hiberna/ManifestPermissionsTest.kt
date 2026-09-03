package com.jinatra.hiberna

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManifestPermissionsTest {

    private fun declared(): List<String> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS,
        )
        return info.requestedPermissions?.toList() ?: emptyList()
    }

    @Test
    fun `never requests INTERNET`() {
        // The privacy policy states hiberna cannot reach the network. Without
        // this permission Android enforces that claim; with it, the policy
        // becomes a promise instead of a property.
        assertFalse(
            "INTERNET must never be declared - see PRIVACY.md",
            declared().contains("android.permission.INTERNET"),
        )
    }

    @Test
    fun `requests QUERY_ALL_PACKAGES, which the app list needs`() {
        assertTrue(declared().contains("android.permission.QUERY_ALL_PACKAGES"))
    }
}
