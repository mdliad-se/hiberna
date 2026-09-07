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
    fun `UNKNOWN never becomes RECOMMENDED or SAFE - it degrades to CAUTION, never a WILL_BREAK claim it cannot support`() {
        // F1: a failed detection must keep behaving conservatively (never
        // RECOMMENDED/SAFE), but the badge must not assert something hiberna
        // does not know - it never ran a successful check, so it cannot say
        // "this will break". CAUTION is the honest middle ground; see
        // severityOf's own doc for why this is independent of the bulk-apply
        // guardrail, which still skips UNKNOWN exactly like LIKELY_BREAKS
        // (see BulkApplierTest).
        assertEquals(
            Severity.CAUTION,
            severityOf(
                sensitivity = Sensitivity.UNKNOWN,
                isSystem = false,
                activity = BackgroundActivity.UNRESTRICTED,
            ),
        )
        assertEquals(
            Severity.CAUTION,
            severityOf(
                sensitivity = Sensitivity.UNKNOWN,
                isSystem = false,
                activity = BackgroundActivity.RESTRICTED,
            ),
        )
    }

    @Test
    fun `UNKNOWN is still CAUTION even for a system app, never promoted to WILL_BREAK`() {
        assertEquals(
            Severity.CAUTION,
            severityOf(
                sensitivity = Sensitivity.UNKNOWN,
                isSystem = true,
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

    // --- H2: RECOMMENDED must not target the exact apps a user deliberately ---
    // --- exempted - a package declaring dataSync/connectedDevice/specialUse ---
    // --- is near-conclusive evidence of a deliberate exemption (a VPN, a    ---
    // --- sync client, a sleep tracker, an automation app), so it is        ---
    // --- demoted to SAFE, never CAUTION: no claim is made either way.       ---

    @Test
    fun `an otherwise-RECOMMENDED app declaring dataSync is demoted to SAFE, not CAUTION`() {
        assertEquals(
            Severity.SAFE,
            severityOf(
                sensitivity = Sensitivity.NONE,
                isSystem = false,
                activity = BackgroundActivity.UNRESTRICTED,
                hasExemptingForegroundServiceType = true,
            ),
        )
    }

    @Test
    fun `an otherwise-RECOMMENDED app declaring connectedDevice is demoted to SAFE, not CAUTION`() {
        // Same assertion as the dataSync case above, kept as its own test
        // rather than folded in: severityOf itself only sees one boolean, so
        // this documents that connectedDevice (like specialUse below) is one
        // of the three types the caller must fold into that boolean - see
        // SensitivityDetectorTest for the per-type bitmask coverage.
        assertEquals(
            Severity.SAFE,
            severityOf(
                sensitivity = Sensitivity.NONE,
                isSystem = false,
                activity = BackgroundActivity.UNRESTRICTED,
                hasExemptingForegroundServiceType = true,
            ),
        )
    }

    @Test
    fun `an otherwise-RECOMMENDED app declaring specialUse is demoted to SAFE, not CAUTION`() {
        assertEquals(
            Severity.SAFE,
            severityOf(
                sensitivity = Sensitivity.NONE,
                isSystem = false,
                activity = BackgroundActivity.UNRESTRICTED,
                hasExemptingForegroundServiceType = true,
            ),
        )
    }

    @Test
    fun `an exempting foreground service type never promotes a non-RECOMMENDED case to anything else`() {
        // The demotion only ever fires on the branch that would otherwise be
        // RECOMMENDED - it must not change a WILL_BREAK, CAUTION or already-SAFE
        // outcome.
        assertEquals(
            Severity.WILL_BREAK,
            severityOf(
                sensitivity = Sensitivity.LIKELY_BREAKS,
                isSystem = false,
                activity = BackgroundActivity.UNRESTRICTED,
                hasExemptingForegroundServiceType = true,
            ),
        )
        assertEquals(
            Severity.CAUTION,
            severityOf(
                sensitivity = Sensitivity.NONE,
                isSystem = true,
                activity = BackgroundActivity.UNRESTRICTED,
                hasExemptingForegroundServiceType = true,
            ),
        )
        assertEquals(
            Severity.SAFE,
            severityOf(
                sensitivity = Sensitivity.NONE,
                isSystem = false,
                activity = BackgroundActivity.RESTRICTED,
                hasExemptingForegroundServiceType = true,
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
    fun `severityOf's four real outputs sort Recommended first, WILL_BREAK last - the whole payoff of the scale`() {
        // F4: unlike a bare check of the enum's declaration order, this
        // actually calls severityOf for one representative input per tier
        // and sorts the results it returns - so it would fail if a future
        // edit reordered the enum without severityOf's mapping agreeing, not
        // just if someone reordered the enum literals by hand.
        val recommended = severityOf(
            sensitivity = Sensitivity.NONE,
            isSystem = false,
            activity = BackgroundActivity.UNRESTRICTED,
        )
        val safe = severityOf(
            sensitivity = Sensitivity.NONE,
            isSystem = false,
            activity = BackgroundActivity.RESTRICTED,
        )
        val caution = severityOf(
            sensitivity = Sensitivity.NONE,
            isSystem = true,
            activity = BackgroundActivity.RESTRICTED,
        )
        val willBreak = severityOf(
            sensitivity = Sensitivity.LIKELY_BREAKS,
            isSystem = false,
            activity = BackgroundActivity.RESTRICTED,
        )

        assertEquals(
            listOf(Severity.RECOMMENDED, Severity.SAFE, Severity.CAUTION, Severity.WILL_BREAK),
            listOf(willBreak, caution, recommended, safe).sortedBy { it.ordinal },
        )
    }
}
