// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jinatra.hiberna.privilege.FakeShizukuPlatform
import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShizukuShellBackend
import com.jinatra.hiberna.shell.realShellBackend
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BootAssertionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `release build with a fake backend crashes`() {
        val e = assertThrows(IllegalStateException::class.java) {
            assertRealBackend(FakeShellBackend(), isDebug = false)
        }
        assertTrue(requireNotNull(e.message).contains("FakeShellBackend"))
    }

    @Test
    fun `release build with a real backend over a fake platform crashes`() {
        // The hole this closes: ShizukuShellBackend(FakeShizukuPlatform())
        // is the right class wrapping a platform that changes nothing. The
        // assertion has to look through the backend at its platform.
        val e = assertThrows(IllegalStateException::class.java) {
            assertRealBackend(ShizukuShellBackend(FakeShizukuPlatform()), isDebug = false)
        }
        assertTrue(requireNotNull(e.message).contains("FakeShizukuPlatform"))
    }

    @Test
    fun `release build with the production wiring is accepted`() {
        assertRealBackend(realShellBackend(context), isDebug = false)
    }

    @Test
    fun `debug build with a fake backend is allowed`() {
        assertRealBackend(FakeShellBackend(), isDebug = true)
    }
}
