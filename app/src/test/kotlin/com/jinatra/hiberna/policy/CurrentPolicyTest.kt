// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentPolicyTest {

    @Test
    fun `battery whitelist takes precedence over an appops restriction on the same package`() {
        // An app can hold both an appops "ignore" and a battery whitelist
        // entry at once. The whitelist is the stronger grant: getting this
        // backwards would show RESTRICTED for an app that is in fact running
        // freely.
        val policy = CurrentPolicy(
            appOpsRestricted = setOf("com.example.both"),
            batteryWhitelisted = setOf("com.example.both"),
            dataBlockedUids = emptySet(),
        )

        assertEquals(BackgroundActivity.UNRESTRICTED, policy.backgroundActivityFor("com.example.both"))
    }

    @Test
    fun `appops restriction alone reports RESTRICTED`() {
        val policy = CurrentPolicy(
            appOpsRestricted = setOf("com.example.restricted"),
            batteryWhitelisted = emptySet(),
            dataBlockedUids = emptySet(),
        )

        assertEquals(BackgroundActivity.RESTRICTED, policy.backgroundActivityFor("com.example.restricted"))
    }

    @Test
    fun `neither lever reports OPTIMIZED`() {
        assertEquals(BackgroundActivity.OPTIMIZED, CurrentPolicy.EMPTY.backgroundActivityFor("com.example.unknown"))
    }
}
