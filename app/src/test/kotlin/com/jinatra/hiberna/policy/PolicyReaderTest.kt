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
    fun `a failing appops command surfaces as failure rather than an empty snapshot`() = runTest {
        val shell = FakeShellBackend().apply {
            script("appops query-op", ShellResult(1, "", "permission denied"))
            script("deviceidle whitelist", ShellResult(0, "", ""))
            script("netpolicy list", ShellResult(0, "", ""))
        }

        val result = PolicyReader(shell).read()

        assertTrue(result.isFailure)
        assertTrue(requireNotNull(result.exceptionOrNull()).message!!.contains("appops"))
    }

    @Test
    fun `a failing deviceidle command surfaces as failure rather than an empty snapshot`() = runTest {
        val shell = FakeShellBackend().apply {
            script("appops query-op", ShellResult(0, "com.example.one", ""))
            script("deviceidle whitelist", ShellResult(1, "", "permission denied"))
            script("netpolicy list", ShellResult(0, "10456", ""))
        }

        val result = PolicyReader(shell).read()

        assertTrue(result.isFailure)
        assertTrue(requireNotNull(result.exceptionOrNull()).message!!.contains("deviceidle"))
    }

    @Test
    fun `a failing netpolicy command surfaces as failure rather than an empty snapshot`() = runTest {
        val shell = FakeShellBackend().apply {
            script("appops query-op", ShellResult(0, "com.example.one", ""))
            script("deviceidle whitelist", ShellResult(0, "user,com.example.two,10456", ""))
            script("netpolicy list", ShellResult(1, "", "permission denied"))
        }

        val result = PolicyReader(shell).read()

        assertTrue(result.isFailure)
        assertTrue(requireNotNull(result.exceptionOrNull()).message!!.contains("netpolicy"))
    }

    @Test
    fun `an empty deviceidle whitelist surfaces as failure, not an empty snapshot`() = runTest {
        // The spike confirmed this list is never empty on a real device: every
        // build ships system packages whitelisted. A 0-exit empty response
        // means the command failed silently.
        val shell = FakeShellBackend().apply {
            script("appops query-op", ShellResult(0, "com.example.one", ""))
            script("deviceidle whitelist", ShellResult(0, "", ""))
            script("netpolicy list", ShellResult(0, "10456", ""))
        }

        val result = PolicyReader(shell).read()

        assertTrue(result.isFailure)
        val message = requireNotNull(result.exceptionOrNull()).message!!
        assertTrue(message.contains("deviceidle"))
        assertTrue(message.contains("empty"))
    }
}
