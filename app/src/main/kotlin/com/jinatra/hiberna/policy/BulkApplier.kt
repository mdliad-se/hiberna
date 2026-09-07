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
 * Per-package detail for one entry in [BulkOutcome.failed]: which lever
 * failed, why, and - critically - [applied], the levers that already landed
 * for this package before that failure (see [ApplyResult.Failed.applied]).
 * There is no rollback, so a package can be "failed" and partially changed at
 * the same time; dropping [applied] here would leave a caller unable to tell
 * those two cases apart and force it to describe a partially-applied app as
 * an outright failure.
 */
data class BulkFailure(
    val lever: String,
    val reason: String,
    val applied: List<String> = emptyList(),
)

/**
 * What actually happened across a whole batch. There is no rollback (see
 * [ApplyResult.Failed]'s own doc), so this cannot be a single pass/fail
 * verdict: [applied] landed, [skipped] were deliberately left alone by the
 * guardrail, and [failed] names, per package, which lever failed, why, and
 * what (if anything) already landed for that package - see [BulkFailure] -
 * a caller that only reported "some apps failed" would leave the user
 * unable to tell which ones actually changed.
 */
data class BulkOutcome(
    val applied: List<String>,
    val skipped: List<String>,
    val failed: Map<String, BulkFailure>,
)

/**
 * The one guardrail predicate: a [BulkTarget] is skipped when [preset] asks
 * to skip sensitive apps, this target is sensitive, and the user has not
 * explicitly overridden this *specific* package (an override must never
 * leak across apps - see the class doc on [BulkApplier]).
 *
 * `sensitivity != Sensitivity.NONE` - not `== Sensitivity.LIKELY_BREAKS` -
 * so [Sensitivity.UNKNOWN] is skipped too (F6): when a detection source
 * threw instead of answering, the one safe assumption is "treat it like a
 * sensitive app until a human says otherwise", never "treat the failure as
 * proof nothing here is sensitive".
 *
 * This is `internal`, not private, and is the only place this decision is
 * expressed: [BulkApplier.apply] and [com.jinatra.hiberna.ui.screens.applist.AppListViewModel.skippedCount]
 * both call this exact function rather than each maintaining their own copy,
 * so a guardrail-preview count shown before the user taps a preset can never
 * drift from what actually gets skipped at apply time.
 */
internal fun BulkTarget.isSkippedByGuardrail(preset: Preset, overridden: Set<String>): Boolean =
    preset.skipSensitive && sensitivity != Sensitivity.NONE && packageName !in overridden

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
        val failed = linkedMapOf<String, BulkFailure>()

        for (target in targets) {
            val pkg = target.packageName
            if (target.isSkippedByGuardrail(preset, overridden)) {
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
                is ApplyResult.Failed ->
                    failed[pkg] = BulkFailure(result.lever, result.reason, result.applied)
            }
        }

        return BulkOutcome(applied = applied, skipped = skipped, failed = failed)
    }
}
