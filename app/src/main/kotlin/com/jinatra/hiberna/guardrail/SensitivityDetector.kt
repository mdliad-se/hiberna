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
 * `RoleManager.getRoleHolders`/`getRoleHoldersAsUser` are `@SystemApi`,
 * gated behind `android.permission.MANAGE_ROLE_HOLDERS` - not part of the
 * public SDK a normal app compiles against, and out of reach anyway since
 * only `shell`/`privilege` may touch anything privilege-shaped (see
 * `arch/PrivilegeBoundaryTest`). Every query below is a public,
 * unprivileged Android API instead.
 */
interface SensitivityDetector {
    suspend fun classify(packageName: String): Sensitivity
}

/**
 * Fallback for responsibilities no unprivileged query below can reach on
 * this device - a backstop, not the mechanism. Each entry exists because a
 * specific detection gap, not because the list tries to be exhaustive:
 */
val DEFAULT_SENSITIVE: Set<String> = setOf(
    // Carries FCM push for most apps' background notifications. There is no
    // role, intent, or account for "background push transport", so losing
    // it silently breaks notifications for apps that look unrelated to it.
    "com.google.android.gms",
    // AOSP's own clock/alarm app (verified: `pm list packages` on a Google
    // Play emulator image resolves ACTION_SET_ALARM to
    // com.google.android.deskclock - Google's fork of this exact package,
    // same "deskclock" suffix, not "alarmclock"). A previous version of this
    // list carried `com.android.alarmclock`, the pre-ICS legacy id; that
    // package has not shipped since Android 2.3 and would never match on a
    // minSdk 30 device. Belt-and-suspenders alongside the ACTION_SET_ALARM
    // query below, for non-Google builds where a device-visibility quirk
    // might make the live query return empty.
    "com.android.deskclock",
    // AOSP's own messaging app - LineageOS and other de-Googled builds
    // default to it; belt-and-suspenders alongside default-SMS detection.
    "com.android.messaging",
    // TOTP-only, no account sync - never registers an AccountManager
    // authenticator, so the authenticator query below can't see it.
    "com.google.android.apps.authenticator2",
    // Push-based MFA prompts, same detection gap as above.
    "com.azure.authenticator",
)

class PlatformSensitivityDetector(
    private val context: Context,
    private val staticList: Set<String> = DEFAULT_SENSITIVE,
) : SensitivityDetector {

    private val defaultSmsPackage: String? by lazy { resolveDefaultSmsPackage() }
    private val defaultDialerPackage: String? by lazy { resolveDefaultDialerPackage() }
    private val alarmApps: Set<String> by lazy { resolveAlarmApps() }
    private val authenticatorPackages: Set<String> by lazy { resolveAuthenticatorPackages() }
    private val homePackage: String? by lazy { resolveHomePackage() }
    private val assistantPackages: Set<String> by lazy { resolveAssistantPackages() }
    private val foregroundServiceTypesByPackage: Map<String, Int> by lazy { resolveForegroundServiceTypes() }

    override suspend fun classify(packageName: String): Sensitivity = withContext(Dispatchers.IO) {
        val flagged = packageName in staticList ||
            packageName == defaultSmsPackage ||
            packageName == defaultDialerPackage ||
            packageName in alarmApps ||
            packageName in authenticatorPackages ||
            packageName == homePackage ||
            packageName in assistantPackages ||
            hasQualifyingForegroundServiceType(foregroundServiceTypesByPackage[packageName] ?: 0)
        if (flagged) Sensitivity.LIKELY_BREAKS else Sensitivity.NONE
    }

    // The app the user actually relies on for SMS/MMS/RCS notifications,
    // on this device, in whatever locale or OEM skin it runs.
    private fun resolveDefaultSmsPackage(): String? =
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

    // The app that surfaces incoming-call UI and missed-call notifications.
    private fun resolveDefaultDialerPackage(): String? =
        runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()

    // Any app that can handle "set an alarm" is functioning as this
    // device's alarm clock, regardless of who publishes it.
    private fun resolveAlarmApps(): Set<String> = runCatching {
        context.packageManager
            .queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    // Apps registered to handle account sign-in (2FA/SSO apps commonly are)
    // even when they hold no accounts themselves.
    private fun resolveAuthenticatorPackages(): Set<String> = runCatching {
        AccountManager.get(context).authenticatorTypes.map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    // The app that renders the home screen / app drawer. Killed in the
    // background, the user loses their launcher - about as loud a breakage
    // as this detector can flag.
    private fun resolveHomePackage(): String? = runCatching {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
    }.getOrNull()

    // Voice-assistant-capable apps. A "voice assistant killed in the
    // background" is exactly the failure mode this detector exists to catch
    // - the user asks it a question, gets silence, and never connects that
    // to the restriction they applied.
    private fun resolveAssistantPackages(): Set<String> = runCatching {
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_ASSIST), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    // Device-wide, computed once - unlike the per-package
    // `getPackageInfo(pkg, GET_SERVICES)` Binder call this used to make on
    // every classify(), this is one `getInstalledPackages(GET_SERVICES)`
    // query cached in a `by lazy` field, exactly like every other source in
    // this class. The bulk-apply path (Task 12) calls classify() for
    // hundreds of packages in a row; that used to mean hundreds of extra
    // IPCs, now it means one.
    //
    // Maps each package to the bitwise OR of every one of its services'
    // `foregroundServiceType`. That is equivalent to the old "any single
    // service qualifies" check - `a and (b or c) == (a and b) or (a and c)`,
    // so ORing every service's type together and masking once against
    // [QUALIFYING_FGS_TYPES] is nonzero iff at least one individual service
    // would have been - just computed from one query instead of N.
    private fun resolveForegroundServiceTypes(): Map<String, Int> = runCatching {
        context.packageManager
            .getInstalledPackages(PackageManager.GET_SERVICES)
            .associate { info ->
                val combined = info.services.orEmpty().fold(0) { acc, service ->
                    acc or service.foregroundServiceType
                }
                info.packageName to combined
            }
    }.getOrDefault(emptyMap())
}

// LOCATION and MEDIA_PLAYBACK have existed since API 29, below this app's
// minSdk 30, so they are always safe to reference. HEALTH was only added in
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
 * so it is unit-testable with plain `Int` literals. Its only previous
 * coverage reflectively set a private [ServiceInfo] field and silently
 * returned with zero assertions if that reflection ever broke - a test that
 * can stop testing while still reporting green. This function has no such
 * escape hatch: give it an `Int`, get a `Boolean`, no PackageManager, no
 * reflection, nothing to silently stop working.
 */
internal fun hasQualifyingForegroundServiceType(foregroundServiceType: Int): Boolean =
    foregroundServiceType and QUALIFYING_FGS_TYPES != 0

class FakeSensitivityDetector(private val sensitive: Set<String>) : SensitivityDetector {
    override suspend fun classify(packageName: String): Sensitivity =
        if (packageName in sensitive) Sensitivity.LIKELY_BREAKS else Sensitivity.NONE
}
