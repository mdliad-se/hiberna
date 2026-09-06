// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.guardrail

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SensitivityDetectorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `flags a package on the static fallback list`() = runTest {
        val detector = PlatformSensitivityDetector(context, staticList = setOf("com.example.sms"))

        assertEquals(Sensitivity.LIKELY_BREAKS, detector.classify("com.example.sms"))
    }

    @Test
    fun `leaves an ordinary package unflagged`() = runTest {
        val detector = PlatformSensitivityDetector(context, staticList = setOf("com.example.sms"))

        assertEquals(Sensitivity.NONE, detector.classify("com.example.game"))
    }

    @Test
    fun `default list covers the categories that break loudest`() {
        listOf("alarm", "clock", "messag", "auth").forEach { needle ->
            assert(DEFAULT_SENSITIVE.any { it.contains(needle) }) { "no default entry matching $needle" }
        }
    }
}
