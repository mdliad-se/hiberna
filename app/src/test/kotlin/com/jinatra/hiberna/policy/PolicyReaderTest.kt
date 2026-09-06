// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

import com.jinatra.hiberna.shell.FakeShellBackend
import com.jinatra.hiberna.shell.ShellResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyReaderTest {

    @Test
    fun `reads all three levers into one snapshot`() = runTest {
        val shell = FakeShellBackend().apply {
            script("appops query-op", ShellResult(0, "com.example.one", ""))
            script("deviceidle whitelist", ShellResult(0, "user,com.example.two,10456", ""))
            script("netpolicy list", ShellResult(0, "10456", ""))
        }

        val policy = PolicyReader(shell).read().getOrThrow()

        assertEquals(setOf("com.example.one"), policy.appOpsRestricted)
        assertEquals(setOf("com.example.two"), policy.batteryWhitelisted)
        assertEquals(setOf(10456), policy.dataBlockedUids)
    }

    @Test
    fun `a failing command surfaces as failure rather than an empty snapshot`() = runTest {
        val shell = FakeShellBackend().apply {
            script("appops query-op", ShellResult(1, "", "permission denied"))
            script("deviceidle whitelist", ShellResult(0, "", ""))
            script("netpolicy list", ShellResult(0, "", ""))
        }

        val result = PolicyReader(shell).read()

        assertTrue(result.isFailure)
        assertTrue(requireNotNull(result.exceptionOrNull()).message!!.contains("appops"))
    }
}
