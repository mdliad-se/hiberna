// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

import com.jinatra.hiberna.apps.FakeAppRepository
import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.apps.InstalledAppRepository
import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyApplierTest {

    private val app = InstalledApp("com.example.app", "Example", 10456, isSystem = false, isEnabled = true)

    private fun applier(shell: FakeShellBackend) =
        PolicyApplier(shell, FakeAppRepository(listOf(app)))

    private fun okShell() = FakeShellBackend().apply {
        script("appops set", ShellResult(0, "", ""))
        script("deviceidle whitelist", ShellResult(0, "", ""))
        script("netpolicy", ShellResult(0, "", ""))
    }

    @Test
    fun `restricted sets appops to ignore and removes the whitelist entry`() = runTest {
        val shell = okShell()

        val result = applier(shell).apply(
            AppPolicy("com.example.app", BackgroundActivity.RESTRICTED, restrictBackgroundData = false)
        )

        assertEquals(ApplyResult.Success, result)
        val joined = shell.executed.map { it.joinToString(" ") }
        assertTrue(joined.any { it.contains("appops set com.example.app RUN_ANY_IN_BACKGROUND ignore") })
        assertTrue(joined.any { it.contains("deviceidle whitelist -com.example.app") })
    }

    @Test
    fun `optimized allows appops and removes the whitelist entry`() = runTest {
        val shell = okShell()

        applier(shell).apply(
            AppPolicy("com.example.app", BackgroundActivity.OPTIMIZED, restrictBackgroundData = false)
        )

        val joined = shell.executed.map { it.joinToString(" ") }
        assertTrue(joined.any { it.contains("RUN_ANY_IN_BACKGROUND allow") })
        assertTrue(joined.any { it.contains("deviceidle whitelist -com.example.app") })
    }

    @Test
    fun `unrestricted allows appops and adds the whitelist entry`() = runTest {
        val shell = okShell()

        applier(shell).apply(
            AppPolicy("com.example.app", BackgroundActivity.UNRESTRICTED, restrictBackgroundData = false)
        )

        val joined = shell.executed.map { it.joinToString(" ") }
        assertTrue(joined.any { it.contains("RUN_ANY_IN_BACKGROUND allow") })
        assertTrue(joined.any { it.contains("deviceidle whitelist +com.example.app") })
    }

    @Test
    fun `data restriction resolves the uid fresh rather than trusting stored state`() = runTest {
        val shell = okShell()

        applier(shell).apply(
            AppPolicy("com.example.app", BackgroundActivity.OPTIMIZED, restrictBackgroundData = true)
        )

        val joined = shell.executed.map { it.joinToString(" ") }
        assertTrue(joined.any { it.contains("netpolicy add restrict-background-blacklist 10456") })
    }

    @Test
    fun `an uninstalled package fails on the data lever instead of guessing a uid`() = runTest {
        val applier = PolicyApplier(okShell(), FakeAppRepository(emptyList()))

        val result = applier.apply(
            AppPolicy("com.gone.away", BackgroundActivity.OPTIMIZED, restrictBackgroundData = true)
        )

        assertTrue(result is ApplyResult.Failed)
        assertEquals("data", (result as ApplyResult.Failed).lever)
    }

    @Test
    fun `a failing lever reports which one failed`() = runTest {
        val shell = FakeShellBackend().apply {
            script("appops set", ShellResult(1, "", "denied"))
        }

        val result = applier(shell).apply(
            AppPolicy("com.example.app", BackgroundActivity.RESTRICTED, restrictBackgroundData = false)
        )

        assertTrue(result is ApplyResult.Failed)
        assertEquals("appops", (result as ApplyResult.Failed).lever)
    }

    // --- Additional coverage beyond the brief ---

    /**
     * [InstalledApp.uid] is a construction-time snapshot; [InstalledAppRepository.uidOf]
     * must be consulted at apply time instead. This repository changes its answer
     * *after* the applier is built, so if [PolicyApplier] ever captured a uid early
     * (e.g. from the app list at construction) this test would still see the stale
     * value and fail.
     */
    private class MutableUidRepository(private var uid: Int?) : InstalledAppRepository {
        fun changeUidTo(newUid: Int?) {
            uid = newUid
        }

        override suspend fun load(): List<InstalledApp> = emptyList()
        override suspend fun uidOf(packageName: String): Int? = uid
    }

    @Test
    fun `uid is re-resolved at apply time, not captured when the applier is built`() = runTest {
        val shell = okShell()
        val repo = MutableUidRepository(uid = 111)
        val policyApplier = PolicyApplier(shell, repo)

        // Change what the repository reports *after* construction, before apply.
        repo.changeUidTo(999)

        val result = policyApplier.apply(
            AppPolicy("com.example.app", BackgroundActivity.OPTIMIZED, restrictBackgroundData = true)
        )

        assertEquals(ApplyResult.Success, result)
        val joined = shell.executed.map { it.joinToString(" ") }
        assertTrue(
            "expected the freshly-resolved uid 999 in a netpolicy command, got $joined",
            joined.any { it.contains("netpolicy add restrict-background-blacklist 999") },
        )
        assertTrue(
            "the stale uid 111 must never appear in an executed command",
            joined.none { it.contains("111") },
        )
    }

    @Test
    fun `a failure reports which levers already landed before it, for a mixed device state`() = runTest {
        val shell = FakeShellBackend().apply {
            script("appops set", ShellResult(0, "", ""))
            script("deviceidle whitelist", ShellResult(1, "", "battery write denied"))
        }

        val result = applier(shell).apply(
            AppPolicy("com.example.app", BackgroundActivity.UNRESTRICTED, restrictBackgroundData = true)
        )

        assertTrue(result is ApplyResult.Failed)
        val failed = result as ApplyResult.Failed
        assertEquals("battery", failed.lever)
        assertEquals(listOf("appops"), failed.applied)
    }

    @Test
    fun `a successful appops and battery write is reported even when data then fails`() = runTest {
        val shell = FakeShellBackend().apply {
            script("appops set", ShellResult(0, "", ""))
            script("deviceidle whitelist", ShellResult(0, "", ""))
            script("netpolicy", ShellResult(1, "", "netpolicy write denied"))
        }

        val result = applier(shell).apply(
            AppPolicy("com.example.app", BackgroundActivity.OPTIMIZED, restrictBackgroundData = true)
        )

        assertTrue(result is ApplyResult.Failed)
        val failed = result as ApplyResult.Failed
        assertEquals("data", failed.lever)
        assertEquals(listOf("appops", "battery"), failed.applied)
    }

    @Test
    fun `nothing is reported applied when appops itself is the first and only failure`() = runTest {
        val shell = FakeShellBackend().apply {
            script("appops set", ShellResult(1, "", "denied"))
        }

        val result = applier(shell).apply(
            AppPolicy("com.example.app", BackgroundActivity.RESTRICTED, restrictBackgroundData = false)
        )

        assertTrue(result is ApplyResult.Failed)
        assertEquals(emptyList<String>(), (result as ApplyResult.Failed).applied)
    }
}
