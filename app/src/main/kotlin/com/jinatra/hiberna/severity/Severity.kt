// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.severity

import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.guardrail.isSensitive
import com.jinatra.hiberna.policy.BackgroundActivity

/**
 * The four-tier severity scale - see
 * `docs/spine/specs/2026-09-07-hiberna-v1.1-design.md` section 1. Declared in
 * "where do I start" order deliberately: [Severity.entries]`.sortedBy { it.ordinal }`
 * *is* the list's sort order (Recommended first, tiebreak alphabetical by
 * label - see `AppListViewModel.reproject`), so this enum's declaration
 * order is not incidental, it is the payoff the whole feature exists for.
 */
enum class Severity {
    /**
     * Free to run all night with no reason to be - battery-whitelisted (or
     * appops-unrestricted) and nothing sensitive detected. Deliberately
     * opinionated: hiberna is actively suggesting a restriction here, and
     * will occasionally suggest one for an app the owner would rather keep.
     * That is an accepted trade, not a bug - see the task brief.
     */
    RECOMMENDED,

    /** Ordinary app. No background signal was detected, and no whitelist entry either. */
    SAFE,

    /**
     * Restrict only if you know what it does. Two, and only two, drivers -
     * see [severityOf]: a system app, not itself flagged as sensitive, but
     * commonly load-bearing for the OS in ways this detector cannot
     * enumerate; or [Sensitivity.UNKNOWN] - a detection source threw instead
     * of answering, so hiberna genuinely does not know whether this app is
     * sensitive. The badge text for the two must read differently ("Caution"
     * vs "Couldn't check this app" - see `AppRow.kt`): the first is a
     * judgment call, the second is an admission, and collapsing them into one
     * sentence would claim a check happened when it did not.
     */
    CAUTION,

    /**
     * Something visible stops working. The old two-value guardrail's
     * [Sensitivity.LIKELY_BREAKS] - a role holder, an authenticator, an alarm
     * handler, or a foreground service declaring location, health *or media
     * playback* - promoted to the top tier of this scale rather than living
     * as a parallel concept. [Sensitivity.UNKNOWN] does **not** land here
     * (see [severityOf]): a failed detection is not a confirmed hit, so it
     * only earns [CAUTION], never this tier's stronger claim.
     */
    WILL_BREAK,
}

/**
 * The pure decision behind the severity scale. Three signals, all already
 * read elsewhere in the app for other reasons - see the class docs on
 * [Sensitivity], [BackgroundActivity] and `InstalledApp.isSystem` - combined
 * here for the first time as one opinionated ranking. No new signal is read
 * to compute this: the honesty rule (`docs/spine/specs/2026-09-07-hiberna-v1.1-design.md`)
 * requires every tier to be derived from state read back from the system,
 * never from anything the user requested.
 *
 * Order of the `when` below is priority order, most severe first:
 *
 * 1. [sensitivity] `== `[Sensitivity.LIKELY_BREAKS] wins outright:
 *    [Severity.WILL_BREAK]. This is also where a media-playback-only
 *    foreground service lands - the spec's table was corrected on
 *    2026-09-07 to put it here, not under [Severity.CAUTION], because
 *    restricting a music player stops background audio exactly as visibly as
 *    losing turn-by-turn navigation; [Sensitivity] already folds
 *    media-playback, location and health foreground-service types into the
 *    same [Sensitivity.LIKELY_BREAKS] boolean before this function ever sees
 *    it, so all three ride together here.
 * 2. Otherwise, [sensitivity]`.`[isSensitive] - i.e. [Sensitivity.UNKNOWN] -
 *    is [Severity.CAUTION], not [Severity.WILL_BREAK]. A detection source
 *    threw instead of answering, and hiberna does not get to claim "this
 *    will break" about a check it never completed. This is deliberately
 *    **not** the same predicate `isSkippedByGuardrail` uses to decide
 *    whether a bulk apply touches the package at all - that guardrail still
 *    treats [Sensitivity.UNKNOWN] exactly like a confirmed hit (see its own
 *    doc). The two questions are independent: whether it is safe to *write* a
 *    restriction, and what the *badge* is honest to claim, do not have to
 *    agree, and forcing them to is what produced this finding in the first
 *    place - see the task report.
 * 3. [isSystem] next - a system app is never [Severity.RECOMMENDED] just
 *    because it happens to already be unrestricted.
 * 4. Otherwise, [activity] `== `[BackgroundActivity.UNRESTRICTED] (battery
 *    whitelisted, or appops-allowed with a whitelist entry - see
 *    `CurrentPolicy.backgroundActivityFor`, where the whitelist already wins
 *    over the appops flag) is [Severity.RECOMMENDED] - **unless**
 *    [hasExemptingForegroundServiceType] is true (H2, added after the tier
 *    first shipped): a whitelist entry on a user-installed app is
 *    near-conclusive evidence someone deliberately exempted it (a VPN, a
 *    sync client, a sleep tracker, an automation app), and a package
 *    declaring `dataSync`, `connectedDevice` or `specialUse` - see
 *    [com.jinatra.hiberna.guardrail.SensitivityDetector.declaresExemptingForegroundServiceType] -
 *    is exactly the shape of app that gets exempted for a reason this
 *    detector cannot see. Demoted to [Severity.SAFE], not [Severity.CAUTION]:
 *    no claim is being made about the package one way or the other, hiberna
 *    simply has no business recommending a restriction for it.
 * 5. Anything left over is [Severity.SAFE].
 */
fun severityOf(
    sensitivity: Sensitivity,
    isSystem: Boolean,
    activity: BackgroundActivity,
    hasExemptingForegroundServiceType: Boolean = false,
): Severity = when {
    sensitivity == Sensitivity.LIKELY_BREAKS -> Severity.WILL_BREAK
    sensitivity.isSensitive -> Severity.CAUTION
    isSystem -> Severity.CAUTION
    activity == BackgroundActivity.UNRESTRICTED && !hasExemptingForegroundServiceType -> Severity.RECOMMENDED
    else -> Severity.SAFE
}
