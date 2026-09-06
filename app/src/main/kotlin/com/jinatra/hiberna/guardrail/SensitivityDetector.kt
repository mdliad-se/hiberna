// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.guardrail

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
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
    // Legacy/forked-AOSP alarm app some non-Google ROMs still ship;
    // belt-and-suspenders alongside the ACTION_SET_ALARM query below.
    "com.android.alarmclock",
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

    override suspend fun classify(packageName: String): Sensitivity = withContext(Dispatchers.IO) {
        val flagged = packageName in staticList ||
            packageName == defaultSmsPackage ||
            packageName == defaultDialerPackage ||
            packageName in alarmApps ||
            packageName in authenticatorPackages
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
}

class FakeSensitivityDetector(private val sensitive: Set<String>) : SensitivityDetector {
    override suspend fun classify(packageName: String): Sensitivity =
        if (packageName in sensitive) Sensitivity.LIKELY_BREAKS else Sensitivity.NONE
}
