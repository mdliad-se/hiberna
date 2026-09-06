// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.preset.Preset

/**
 * One package targeted by a bulk apply. Deliberately holds only what
 * [BulkApplier] needs to make the guardrail decision and issue the write -
 * never the UI's `AppRowState`, which also carries `activity`/`dataBlocked`
 * fields the domain layer has no business depending on. The view model maps
 * rows to these at the boundary.
 */
data class BulkTarget(
    val packageName: String,
    val sensitivity: Sensitivity,
)

/**
 * What actually happened across a whole batch. There is no rollback (see
 * [ApplyResult.Failed]'s own doc), so this cannot be a single pass/fail
 * verdict: [applied] landed, [skipped] were deliberately left alone by the
 * guardrail, and [failed] names, per package, which lever failed and why -
 * a caller that only reported "some apps failed" would leave the user
 * unable to tell which ones actually changed.
 */
data class BulkOutcome(
    val applied: List<String>,
    val skipped: List<String>,
    val failed: Map<String, String>,
)

/**
 * Applies one [Preset] across many packages.
 *
 * Two rules make this safe. A sensitive package is skipped unless the user
 * has explicitly overridden that specific package (see [overridden]) - this
 * is what stops a bulk apply from silently killing messaging notifications
 * or alarms, the exact failure mode a user never connects back to "I tapped
 * a preset". And one package's failure never aborts the batch: every
 * remaining target is still attempted, and the honest, partial result is
 * reported back rather than one opaque "it failed".
 */
class BulkApplier(private val applier: PolicyApplier) {

    suspend fun apply(
        targets: List<BulkTarget>,
        preset: Preset,
        overridden: Set<String>,
    ): BulkOutcome {
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()

        for (target in targets) {
            val pkg = target.packageName
            val isSensitive = target.sensitivity == Sensitivity.LIKELY_BREAKS
            if (preset.skipSensitive && isSensitive && pkg !in overridden) {
                skipped += pkg
                continue
            }

            val result = applier.apply(
                AppPolicy(
                    packageName = pkg,
                    backgroundActivity = preset.backgroundActivity,
                    restrictBackgroundData = preset.restrictBackgroundData,
                )
            )
            when (result) {
                is ApplyResult.Success -> applied += pkg
                is ApplyResult.Failed -> failed[pkg] = "${result.lever}: ${result.reason}"
            }
        }

        return BulkOutcome(applied = applied, skipped = skipped, failed = failed)
    }
}
