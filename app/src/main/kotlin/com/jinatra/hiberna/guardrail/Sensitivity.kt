// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.guardrail

enum class Sensitivity {
    /** Safe to restrict as far as we can tell. */
    NONE,

    /**
     * At least one detection source in [PlatformSensitivityDetector] threw
     * instead of answering, and every source that *did* answer came back
     * negative. Deliberately distinct from [NONE]: a query throwing is not
     * the same fact as a query legitimately finding nothing, and collapsing
     * the two into one value is exactly what would let a
     * `TransactionTooLargeException` or a missing `AccountManager` make
     * every app on the device look silently safe to restrict. The guardrail
     * (`isSkippedByGuardrail`) treats this the same as [LIKELY_BREAKS] -
     * degrade conservatively, never degrade silently into unsafe.
     */
    UNKNOWN,

    /** Restricting this will probably break something the user relies on. */
    LIKELY_BREAKS,
}

/**
 * The one "is this app sensitive" test - not [LIKELY_BREAKS] alone, so a
 * detection failure ([UNKNOWN]) is never treated the same as "nothing
 * detected" ([NONE]). Both [com.jinatra.hiberna.policy.BulkApplier.isSkippedByGuardrail]
 * and [com.jinatra.hiberna.severity.severityOf] call this exact property
 * rather than each re-typing `sensitivity != Sensitivity.NONE` - a future
 * [Sensitivity] value only has to change what "sensitive" means in one place
 * for the guardrail-preview count, the actual skip decision and the badge to
 * all agree again.
 */
val Sensitivity.isSensitive: Boolean
    get() = this != Sensitivity.NONE
