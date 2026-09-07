// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.guardrail

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.AlarmClock
import android.provider.Telephony
import android.telecom.TelecomManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Detected, not hardcoded. A static list goes stale and never covers other
 * locales or OEM apps; asking the system what actually holds each
 * responsibility on THIS device survives across both.
 *
 * RoleManager.getRoleHolders/getRoleHoldersAsUser are SystemApi,
 * gated behind android.permission.MANAGE_ROLE_HOLDERS - not part of the
 * public SDK a normal app compiles against, and out of reach anyway since
 * only shell/privilege may touch anything privilege-shaped (see
 * arch/PrivilegeBoundaryTest). Every query below is a public,
 * unprivileged Android API instead.
 */
interface SensitivityDetector {
    suspend fun classify(packageName: String): Sensitivity

    /**
     * H2: does [packageName] declare a foreground service type - `dataSync`,
     * `connectedDevice` or `specialUse` (see [EXEMPTING_FGS_TYPES]) - that is
     * near-conclusive evidence someone (a VPN, a sync client, a sleep
     * tracker, an automation app) deliberately keeps this app running in the
     * background? [severityOf][com.jinatra.hiberna.severity.severityOf] uses
     * this to demote such a package out of [com.jinatra.hiberna.severity.Severity.RECOMMENDED]
     * into [com.jinatra.hiberna.severity.Severity.SAFE] - never
     * [com.jinatra.hiberna.severity.Severity.CAUTION], because no claim is
     * being made about the package, hiberna simply has no business
     * recommending a restriction for it. This is independent of [classify]:
     * a package can be [Sensitivity.NONE] and still declare an exempting
     * type, and the two questions (is it sensitive vs. is there evidence of
     * a deliberate exemption) do not have to agree.
     */
    suspend fun declaresExemptingForegroundServiceType(packageName: String): Boolean
}

/**
 * Fallback for responsibilities no unprivileged query below can reach on
 * this device - a backstop, not the mechanism. Each entry exists because a
 * specific detection gap, not because the list tries to be exhaustive:
 */
val DEFAULT_SENSITIVE: Set<String> = setOf(
    // Carries FCM delivery for most apps background notifications. There is no
    // role, intent, or account for background transport, so losing
    // it silently breaks notifications for apps that look unrelated to it.
    "com.google.android.gms",
    // The AOSP clock/alarm app (verified: pm list packages on a Google
    // Play emulator image resolves ACTION_SET_ALARM to
    // com.google.android.deskclock, a Google-authored fork of this exact
    // package, same deskclock suffix, not alarmclock). A previous version
    // of this list carried com.android.alarmclock, the pre-ICS legacy id;
    // that package has not shipped since Android 2.3 and would never match
    // on a minSdk 30 device. Belt-and-suspenders alongside the
    // ACTION_SET_ALARM query below, for non-Google builds where a
    // device-visibility quirk might make the live query return empty.
    "com.android.deskclock",
    // The AOSP messaging app - LineageOS and other de-Googled builds
    // default to it; belt-and-suspenders alongside default-SMS detection.
    "com.android.messaging",
    // TOTP-only, no account sync - never registers an AccountManager
    // authenticator, so the authenticator query below cannot see it.
    "com.google.android.apps.authenticator2",
    // Notification-based MFA prompts, same detection gap as above.
    "com.azure.authenticator",
)

class PlatformSensitivityDetector(
    private val context: Context,
    private val staticList: Set<String> = DEFAULT_SENSITIVE,
) : SensitivityDetector {

    // Each source is captured as a Result, not unwrapped with a silent
    // getOrDefault at the field itself - see classify() own doc below for why
    // a source that threw must stay distinguishable, all the way through to
    // the final Sensitivity, from a source that legitimately found nothing.
    private val defaultSmsResult: Result<String?> by lazy {
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }
    }
    private val defaultDialerResult: Result<String?> by lazy {
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }
    }
    private val alarmAppsResult: Result<Set<String>> by lazy { runCatching { resolveAlarmApps() } }
    private val authenticatorResult: Result<Set<String>> by lazy { runCatching { resolveAuthenticatorPackages() } }
    private val homePackageResult: Result<String?> by lazy { runCatching { resolveHomePackage() } }
    private val assistantResult: Result<Set<String>> by lazy { runCatching { resolveAssistantPackages() } }
    private val foregroundServiceTypesResult: Result<Map<String, Int>> by lazy {
        runCatching { resolveForegroundServiceTypes() }
    }

    /**
     * Every source above used to be a plain runCatching call defaulting to
     * empty at the field itself - the review finding this fixes (F6): a
     * source that throws (a TransactionTooLargeException from
     * getInstalledPackages(GET_SERVICES) on a package-heavy device;
     * AccountManager.get(context) throwing on some OEM builds) was
     * indistinguishable from a source that legitimately found nothing, so a
     * classify() call could silently collapse to Sensitivity.NONE for a
     * package a live source would have flagged, purely because some other,
     * unrelated source happened to throw first.
     *
     * So each source above is captured as a Result, and this function
     * checks both: flagged is computed from whatever did answer (a flag
     * from one surviving source must never be erased by a different source
     * failing - see the "a throwing source degrades to the remaining
     * sources" test), and anyFailed records whether any source threw at
     * all. A package flagged by a live source is still Sensitivity.LIKELY_BREAKS
     * regardless of anyFailed - a false positive there costs nothing, the
     * guardrail already treats it as sensitive. Only the negative case
     * matters: nothing flagged it is Sensitivity.NONE when every source
     * genuinely got to answer, but Sensitivity.UNKNOWN - treated the same
     * as LIKELY_BREAKS by the guardrail, never silently the same as NONE -
     * when at least one source could not be trusted to have looked.
     */
    override suspend fun classify(packageName: String): Sensitivity = withContext(Dispatchers.IO) {
        val results = listOf(
            defaultSmsResult, defaultDialerResult, alarmAppsResult,
            authenticatorResult, homePackageResult, assistantResult, foregroundServiceTypesResult,
        )
        val anyFailed = results.any { it.isFailure }
        val flagged = packageName in staticList ||
            packageName == defaultSmsResult.getOrNull() ||
            packageName == defaultDialerResult.getOrNull() ||
            packageName in alarmAppsResult.getOrDefault(emptySet()) ||
            packageName in authenticatorResult.getOrDefault(emptySet()) ||
            packageName == homePackageResult.getOrNull() ||
            packageName in assistantResult.getOrDefault(emptySet()) ||
            hasQualifyingForegroundServiceType(
                foregroundServiceTypesResult.getOrDefault(emptyMap())[packageName] ?: 0,
            )
        when {
            flagged -> Sensitivity.LIKELY_BREAKS
            anyFailed -> Sensitivity.UNKNOWN
            else -> Sensitivity.NONE
        }
    }

    // Any app that can handle set an alarm is functioning as this
    // device alarm clock, regardless of who publishes it.
    private fun resolveAlarmApps(): Set<String> =
        context.packageManager
            .queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()

    // Apps registered to handle account sign-in (2FA/SSO apps commonly are)
    // even when they hold no accounts themselves.
    private fun resolveAuthenticatorPackages(): Set<String> =
        AccountManager.get(context).authenticatorTypes.map { it.packageName }.toSet()

    // The app that renders the home screen / app drawer. Killed in the
    // background, the user loses their launcher - about as loud a breakage
    // as this detector can flag.
    private fun resolveHomePackage(): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
    }

    // Voice-assistant-capable apps. A voice assistant killed in the
    // background is exactly the failure mode this detector exists to catch
    // - the user asks it a question, gets silence, and never connects that
    // to the restriction they applied.
    private fun resolveAssistantPackages(): Set<String> =
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_ASSIST), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()

    // Device-wide, computed once - unlike the per-package
    // getPackageInfo(pkg, GET_SERVICES) Binder call this used to make on
    // every classify(), this is one getInstalledPackages(GET_SERVICES)
    // query cached in a by-lazy field, exactly like every other source in
    // this class. The bulk-apply path (Task 12) calls classify() for
    // hundreds of packages in a row; that used to mean hundreds of extra
    // IPCs, now it means one.
    //
    // Maps each package to the bitwise OR of the foregroundServiceType of
    // every one of its services. That is equivalent to the old any single
    // service qualifies check - a and (b or c) == (a and b) or (a and c),
    // so ORing every service type together and masking once against
    // QUALIFYING_FGS_TYPES is nonzero iff at least one individual service
    // would have been - just computed from one query instead of N.
    private fun resolveForegroundServiceTypes(): Map<String, Int> =
        context.packageManager
            .getInstalledPackages(PackageManager.GET_SERVICES)
            .associate { info ->
                val combined = info.services.orEmpty().fold(0) { acc, service ->
                    acc or service.foregroundServiceType
                }
                info.packageName to combined
            }

    // H2: reuses the exact same cached, device-wide query classify() already
    // pays for above - no second getInstalledPackages(GET_SERVICES) Binder
    // call just to answer this second question about the same data.
    override suspend fun declaresExemptingForegroundServiceType(packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            hasExemptingForegroundServiceType(
                foregroundServiceTypesResult.getOrDefault(emptyMap())[packageName] ?: 0,
            )
        }
}

// LOCATION and MEDIA_PLAYBACK have existed since API 29, below minSdk 30 for
// this app, so they are always safe to reference. HEALTH was only added in
// API 34; the bit is still a compile-time constant (inlined by the
// compiler, so referencing it never throws), but a service parsed on an
// API < 34 device can never have that bit set in the first place, so the
// SDK_INT guard below documents that rather than changing behavior.
internal val QUALIFYING_FGS_TYPES: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
    } else {
        0
    }

/**
 * The pure decision behind the foreground-service check above, pulled out
 * so it is unit-testable with plain Int literals. Its only previous
 * coverage reflectively set a private ServiceInfo field and silently
 * returned with zero assertions if that reflection ever broke - a test that
 * can stop testing while still reporting green. This function has no such
 * escape hatch: give it an Int, get a Boolean, no PackageManager, no
 * reflection, nothing to silently stop working.
 */
internal fun hasQualifyingForegroundServiceType(foregroundServiceType: Int): Boolean =
    foregroundServiceType and QUALIFYING_FGS_TYPES != 0

// H2: DATA_SYNC and CONNECTED_DEVICE have existed since API 29, same as
// LOCATION/MEDIA_PLAYBACK above, so always safe to reference below minSdk 30.
// SPECIAL_USE was only added in API 34, same situation as HEALTH above - the
// constant is still a compile-time literal (never throws to reference), but
// a service parsed on an API < 34 device can never carry that bit, so the
// SDK_INT guard documents that rather than changing behavior.
internal val EXEMPTING_FGS_TYPES: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    } else {
        0
    }

/**
 * The pure decision behind [PlatformSensitivityDetector.declaresExemptingForegroundServiceType]
 * above, unit-testable with plain Int literals for the same reason
 * [hasQualifyingForegroundServiceType] is - see that function's own doc.
 */
internal fun hasExemptingForegroundServiceType(foregroundServiceType: Int): Boolean =
    foregroundServiceType and EXEMPTING_FGS_TYPES != 0

class FakeSensitivityDetector(
    private val sensitive: Set<String>,
    private val exemptingForegroundServiceType: Set<String> = emptySet(),
) : SensitivityDetector {
    override suspend fun classify(packageName: String): Sensitivity =
        if (packageName in sensitive) Sensitivity.LIKELY_BREAKS else Sensitivity.NONE

    override suspend fun declaresExemptingForegroundServiceType(packageName: String): Boolean =
        packageName in exemptingForegroundServiceType
}
