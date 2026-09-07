// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.severity

import com.jinatra.hiberna.guardrail.Sensitivity
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
     * Restrict only if you know what it does. Covers a system app - not
     * itself flagged as sensitive, but system apps are commonly load-bearing
     * for the OS in ways this detector cannot enumerate - and would also
     * cover a media-playback-only foreground service if that signal ever
     * reached here distinguished from [Sensitivity.LIKELY_BREAKS] - see this
     * file's own doc below on why, today, it never does.
     */
    CAUTION,

    /**
     * Something visible stops working. The old two-value guardrail's
     * [Sensitivity.LIKELY_BREAKS]/[Sensitivity.UNKNOWN], promoted to the top
     * tier of this scale rather than living as a parallel concept - see
     * [severityOf].
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
 * 1. [sensitivity] `!= `[Sensitivity.NONE] always wins. This is deliberately
 *    not `== `[Sensitivity.LIKELY_BREAKS]: [Sensitivity.UNKNOWN] means a
 *    detection source threw instead of answering, and the guardrail this
 *    replaces (`isSkippedByGuardrail`) has always treated that the same as a
 *    confirmed hit, never the same as "nothing detected" - an [Sensitivity.UNKNOWN]
 *    app must never earn [Severity.RECOMMENDED] or [Severity.SAFE], and this
 *    ordering is what guarantees that. Note this also means a package whose
 *    only qualifying signal is a media-playback foreground service becomes
 *    [Severity.WILL_BREAK] here, not [Severity.CAUTION] as the spec's table
 *    literally lists it under - see the task report for why: today
 *    [Sensitivity] already folds media-playback, location and health
 *    foreground-service types into the same boolean before this function
 *    ever sees it (`PlatformSensitivityDetector.hasQualifyingForegroundServiceType`),
 *    so there is no way to tell them apart without changing that detector's
 *    existing, separately-tested behaviour, which is out of scope here.
 * 2. [isSystem] next - a system app is never [Severity.RECOMMENDED] just
 *    because it happens to already be unrestricted.
 * 3. Otherwise, [activity] `== `[BackgroundActivity.UNRESTRICTED] (battery
 *    whitelisted, or appops-allowed with a whitelist entry - see
 *    `CurrentPolicy.backgroundActivityFor`, where the whitelist already wins
 *    over the appops flag) is [Severity.RECOMMENDED].
 * 4. Anything left over is [Severity.SAFE].
 */
fun severityOf(
    sensitivity: Sensitivity,
    isSystem: Boolean,
    activity: BackgroundActivity,
): Severity = when {
    sensitivity != Sensitivity.NONE -> Severity.WILL_BREAK
    isSystem -> Severity.CAUTION
    activity == BackgroundActivity.UNRESTRICTED -> Severity.RECOMMENDED
    else -> Severity.SAFE
}
