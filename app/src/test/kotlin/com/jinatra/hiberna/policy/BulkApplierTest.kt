// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

import com.jinatra.hiberna.apps.FakeAppRepository
import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.preset.Preset
import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkApplierTest {

    private fun app(pkg: String, uid: Int) = InstalledApp(pkg, pkg, uid, isSystem = false, isEnabled = true)

    private val gameApp = app("com.example.game", 10456)
    private val smsApp = app("com.example.sms", 10500)

    private val game = BulkTarget("com.example.game", Sensitivity.NONE)
    private val sms = BulkTarget("com.example.sms", Sensitivity.LIKELY_BREAKS)

    private val frugal = Preset("frugal", "Frugal", BackgroundActivity.RESTRICTED, false)

    private fun okShell() = FakeShellBackend().apply {
        script("appops set", ShellResult(0, "", ""))
        script("deviceidle whitelist", ShellResult(0, "", ""))
        script("netpolicy", ShellResult(0, "", ""))
    }

    private fun bulk(shell: FakeShellBackend) =
        BulkApplier(PolicyApplier(shell, FakeAppRepository(listOf(gameApp, smsApp))))

    @Test
    fun `skips sensitive apps by default`() = runTest {
        val outcome = bulk(okShell()).apply(listOf(game, sms), frugal, overridden = emptySet())

        assertEquals(listOf("com.example.game"), outcome.applied)
        assertEquals(listOf("com.example.sms"), outcome.skipped)
    }

    @Test
    fun `an explicit override lets a sensitive app through`() = runTest {
        val outcome = bulk(okShell())
            .apply(listOf(game, sms), frugal, overridden = setOf("com.example.sms"))

        assertTrue(outcome.applied.containsAll(listOf("com.example.game", "com.example.sms")))
        assertTrue(outcome.skipped.isEmpty())
    }

    @Test
    fun `a preset with skipSensitive false applies to everything`() = runTest {
        val reckless = frugal.copy(id = "reckless", skipSensitive = false)

        val outcome = bulk(okShell()).apply(listOf(game, sms), reckless, overridden = emptySet())

        assertEquals(2, outcome.applied.size)
        assertTrue(outcome.skipped.isEmpty())
    }

    @Test
    fun `one failure does not abort the rest of the batch`() = runTest {
        val shell = FakeShellBackend().apply {
            script("appops set com.example.game", ShellResult(1, "", "denied"))
            script("appops set", ShellResult(0, "", ""))
            script("deviceidle whitelist", ShellResult(0, "", ""))
            script("netpolicy", ShellResult(0, "", ""))
        }
        val reckless = frugal.copy(skipSensitive = false)

        val outcome = bulk(shell).apply(listOf(game, sms), reckless, overridden = emptySet())

        assertEquals(listOf("com.example.sms"), outcome.applied)
        assertTrue(outcome.failed.containsKey("com.example.game"))
    }
}
