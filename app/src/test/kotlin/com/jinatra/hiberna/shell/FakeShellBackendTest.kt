// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.shell

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeShellBackendTest {

    @Test
    fun `returns scripted result for a matching command`() = runTest {
        val fake = FakeShellBackend()
        fake.script("appops set", ShellResult(0, "ok", ""))

        val result = fake.exec(listOf("cmd", "appops", "set", "a.b.c", "RUN_ANY_IN_BACKGROUND", "ignore"))

        assertEquals(0, result.exitCode)
        assertEquals("ok", result.stdout)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `records every executed command in order`() = runTest {
        val fake = FakeShellBackend()
        fake.exec(listOf("one"))
        fake.exec(listOf("two"))

        assertEquals(listOf(listOf("one"), listOf("two")), fake.executed)
    }

    @Test
    fun `unscripted command fails loudly rather than returning success`() = runTest {
        val fake = FakeShellBackend()

        val result = fake.exec(listOf("unexpected", "command"))

        assertEquals(127, result.exitCode)
        assertTrue(result.stderr.contains("unscripted"))
    }
}
