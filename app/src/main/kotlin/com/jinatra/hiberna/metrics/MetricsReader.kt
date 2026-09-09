// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.metrics

import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.shell.ShellBackend

/**
 * Joins the two metric sources onto packages.
 *
 * Unlike [com.jinatra.hiberna.policy.PolicyReader], this reader **never
 * fails**: it returns a snapshot with whatever it could measure and nulls for
 * the rest. The difference is deliberate. Policy state drives what the app
 * writes to the system, so a failed read there must stop everything. These two
 * numbers are advisory - losing them should degrade a row, not blank the
 * screen a user came to use.
 */
class MetricsReader(
    private val shell: ShellBackend,
    private val usage: UsageSource,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * [apps] must come from the same enumeration pass as the rows being
     * displayed. `batterystats` reports uids, and mapping a uid back to a
     * package against a stale list would attribute one app's drain to another
     * - see the warning on [InstalledApp.uid].
     */
    suspend fun read(apps: List<InstalledApp>): MetricsSnapshot {
        val battery = readBattery()

        val window = if (battery != null) {
            MetricsWindow(millis = battery.timeOnBatteryMillis, sinceLastCharge = true)
        } else {
            // Runtime does not disappear because the battery parser did.
            // Coupling the reliable metric's availability to the fragile one
            // would be the wrong trade - see section D of the design.
            MetricsWindow(millis = MetricsSnapshot.FALLBACK_WINDOW_MILLIS, sinceLastCharge = false)
        }

        val hasUsageAccess = usage.hasAccess()
        val end = now()
        val foreground = if (hasUsageAccess) {
            usage.foregroundMillis(end - window.millis, end)
        } else {
            null
        }

        // Packages sharing a uid all carry the group's figure, flagged so the
        // UI can say so. Dropping them instead would hide real drain; showing
        // the number unflagged would claim it as this package's own.
        val packagesPerUid = apps.groupBy { it.uid }

        val byPackage = apps.associate { app ->
            val mah = battery?.mahByUid?.get(app.uid)
            app.packageName to AppMetric(
                batteryPercent = if (mah != null) battery.percentOf(app.uid) else null,
                batteryMah = mah,
                foregroundMillis = foreground?.get(app.packageName)
                    // Usage access granted and the window read, but this
                    // package absent from it, means it genuinely was not in
                    // the foreground. That is a measured zero, not a gap.
                    ?: foreground?.let { 0L },
                batteryIsSharedUid = mah != null && (packagesPerUid[app.uid]?.size ?: 0) > 1,
            )
        }

        return MetricsSnapshot(
            window = window,
            byPackage = byPackage,
            needsUsageAccess = !hasUsageAccess,
        )
    }

    private suspend fun readBattery(): BatteryPowerUse? {
        val result = shell.exec(listOf("dumpsys", "batterystats", "--charged"))
        if (!result.isSuccess) return null
        return parseBatteryPowerUse(result.stdout)
    }

    /**
     * Grants this app the appop `UsageStatsManager` needs. Called only after
     * the user accepts the one-time prompt: usage access reveals when every
     * app on the device was opened, which is more personal than the three
     * settings this app otherwise changes.
     */
    suspend fun grantUsageAccess(packageName: String): Boolean =
        shell.exec(listOf("cmd", "appops", "set", packageName, "GET_USAGE_STATS", "allow")).isSuccess

    /** Reverses [grantUsageAccess]. Offered alongside it, so the grant is one tap from undone. */
    suspend fun revokeUsageAccess(packageName: String): Boolean =
        shell.exec(listOf("cmd", "appops", "set", packageName, "GET_USAGE_STATS", "deny")).isSuccess
}
