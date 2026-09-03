package com.jinatra.hiberna

import com.jinatra.hiberna.shell.FakeShellBackend
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BootAssertionTest {

    @Test
    fun `release build with a fake backend crashes`() {
        val e = assertThrows(IllegalStateException::class.java) {
            assertRealBackend(FakeShellBackend(), isDebug = false)
        }
        assertTrue(requireNotNull(e.message).contains("FakeShellBackend"))
    }

    @Test
    fun `debug build with a fake backend is allowed`() {
        assertRealBackend(FakeShellBackend(), isDebug = true)
    }
}
