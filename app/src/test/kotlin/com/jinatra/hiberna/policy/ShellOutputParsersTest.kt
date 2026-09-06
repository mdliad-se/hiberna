// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellOutputParsersTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture $name — capture it in Task 1"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `parses appops query output into package names`() {
        val parsed = parseAppOpsRestricted(fixture("appops_query_restricted.txt"))
        assertEquals(setOf("com.android.vending", "com.jinatra.finatra"), parsed)
    }

    @Test
    fun `appops parser ignores blank lines and trims whitespace`() {
        val raw = "  com.example.one  \n\n\ncom.example.two\n"
        assertEquals(setOf("com.example.one", "com.example.two"), parseAppOpsRestricted(raw))
    }

    @Test
    fun `parses deviceidle whitelist and strips the user and uid columns`() {
        val raw = """
            system,com.android.systemui,10023
            user,com.example.app,10456
        """.trimIndent()
        assertEquals(setOf("com.android.systemui", "com.example.app"), parseDeviceIdleWhitelist(raw))
    }

    @Test
    fun `deviceidle parser handles the real device fixture`() {
        val parsed = parseDeviceIdleWhitelist(fixture("deviceidle_whitelist.txt"))
        assertTrue("device whitelist should never be empty", parsed.isNotEmpty())
    }

    @Test
    fun `parses netpolicy uids from the header line`() {
        // exact shape captured from the device, trailing space included
        val raw = "Restrict background blacklisted UIDs: 10153 10220 \n"
        assertEquals(setOf(10153, 10220), parseNetPolicyBlacklist(raw))
    }

    @Test
    fun `netpolicy none means empty, not a parse failure`() {
        val raw = "Restrict background blacklisted UIDs: none\n"
        assertEquals(emptySet<Int>(), parseNetPolicyBlacklist(raw))
    }

    @Test
    fun `appops No operations is not a package name`() {
        // the spike's most important finding: this string contains a dot, so a
        // naive dot check invents a package called "No operations."
        assertEquals(emptySet<String>(), parseAppOpsRestricted("No operations.\n"))
    }

    @Test
    fun `appops empty response parses as empty`() {
        assertEquals(emptySet<String>(), parseAppOpsRestricted(""))
    }

    @Test
    fun `appops empty fixture parses as empty`() {
        assertEquals(emptySet<String>(), parseAppOpsRestricted(fixture("appops_query_empty.txt")))
    }

    @Test
    fun `netpolicy empty fixture parses as empty`() {
        assertEquals(emptySet<Int>(), parseNetPolicyBlacklist(fixture("netpolicy_blacklist_empty.txt")))
    }

    @Test
    fun `netpolicy real fixture yields the two blacklisted uids`() {
        // replaces a test that called the parser and asserted nothing
        assertEquals(setOf(10153, 10220), parseNetPolicyBlacklist(fixture("netpolicy_blacklist.txt")))
    }
}
