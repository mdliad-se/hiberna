// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.severity

import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.policy.BackgroundActivity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure decision behind the four-tier severity scale - see
 * `docs/spine/specs/2026-09-07-hiberna-v1.1-design.md` section 1 for the
 * table this function implements. Every case below is plain enum literals,
 * no PackageManager, no Shizuku, nothing to fake.
 */
class SeverityTest {

    @Test
    fun `a role holder or otherwise flagged app is WILL_BREAK regardless of anything else`() {
        // isSystem and activity are deliberately set to values that would
        // otherwise earn RECOMMENDED or CAUTION - the sensitive signal must
        // win over both.
        assertEquals(
            Severity.WILL_BREAK,
            severityOf(
                sensitivity = Sensitivity.LIKELY_BREAKS,
                isSystem = false,
                activity = BackgroundActivity.UNRESTRICTED,
            ),
        )
        assertEquals(
            Severity.WILL_BREAK,
            severityOf(
                sensitivity = Sensitivity.LIKELY_BREAKS,
                isSystem = true,
                activity = BackgroundActivity.RESTRICTED,
            ),
        )
    }

    @Test
    fun `UNKNOWN never becomes RECOMMENDED or SAFE - it degrades to WILL_BREAK exactly like LIKELY_BREAKS`() {
        // This is the invariant call out in the task brief: a detection
        // failure must keep behaving conservatively through the severity
        // model, not just through the old two-value guardrail.
        assertEquals(
            Severity.WILL_BREAK,
            severityOf(
                sensitivity = Sensitivity.UNKNOWN,
                isSystem = false,
                activity = BackgroundActivity.UNRESTRICTED,
            ),
        )
        assertEquals(
            Severity.WILL_BREAK,
            severityOf(
                sensitivity = Sensitivity.UNKNOWN,
                isSystem = false,
                activity = BackgroundActivity.RESTRICTED,
            ),
        )
    }

    @Test
    fun `a non-sensitive system app is CAUTION even when battery-whitelisted`() {
        // A system app never earns RECOMMENDED just because it happens to be
        // unrestricted - CAUTION always wins for a system app once the
        // sensitive check has cleared.
        assertEquals(
            Severity.CAUTION,
            severityOf(
                sensitivity = Sensitivity.NONE,
                isSystem = true,
                activity = BackgroundActivity.UNRESTRICTED,
            ),
        )
        assertEquals(
            Severity.CAUTION,
            severityOf(
                sensitivity = Sensitivity.NONE,
                isSystem = true,
                activity = BackgroundActivity.RESTRICTED,
            ),
        )
    }

    @Test
    fun `a non-sensitive non-system unrestricted app is RECOMMENDED`() {
        assertEquals(
            Severity.RECOMMENDED,
            severityOf(
                sensitivity = Sensitivity.NONE,
                isSystem = false,
                activity = BackgroundActivity.UNRESTRICTED,
            ),
        )
    }

    @Test
    fun `a non-sensitive non-system app that is not already unrestricted is SAFE`() {
        assertEquals(
            Severity.SAFE,
            severityOf(
                sensitivity = Sensitivity.NONE,
                isSystem = false,
                activity = BackgroundActivity.RESTRICTED,
            ),
        )
        assertEquals(
            Severity.SAFE,
            severityOf(
                sensitivity = Sensitivity.NONE,
                isSystem = false,
                activity = BackgroundActivity.OPTIMIZED,
            ),
        )
    }

    @Test
    fun `tiers sort Recommended first, WILL_BREAK last - the whole payoff of the scale`() {
        val expectedOrder = listOf(
            Severity.RECOMMENDED,
            Severity.SAFE,
            Severity.CAUTION,
            Severity.WILL_BREAK,
        )
        assertEquals(expectedOrder, Severity.values().sortedBy { it.ordinal })
    }
}
