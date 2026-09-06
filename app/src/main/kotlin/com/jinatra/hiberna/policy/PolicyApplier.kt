// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

import com.jinatra.hiberna.apps.InstalledAppRepository
import com.jinatra.hiberna.shell.ShellBackend

/**
 * Turns a desired [AppPolicy] into the shell commands that change it on the
 * device, through the injected [ShellBackend] only — see
 * `arch/PrivilegeBoundaryTest` for the rule this class must not break.
 *
 * Ordering is deliberate: appops runs first because it is the lever users
 * care about most, and if it fails nothing else is attempted, so the app
 * never reports partial success it cannot describe. The uid for the data
 * lever is resolved via [InstalledAppRepository.uidOf] at apply time —
 * never read from a cached [com.jinatra.hiberna.apps.InstalledApp.uid] —
 * because a stale uid silently restricts the wrong app.
 */
class PolicyApplier(
    private val shell: ShellBackend,
    private val apps: InstalledAppRepository,
) {

    suspend fun apply(policy: AppPolicy): ApplyResult {
        val pkg = policy.packageName
        val applied = mutableListOf<String>()

        val appOpsMode = when (policy.backgroundActivity) {
            BackgroundActivity.RESTRICTED -> "ignore"
            BackgroundActivity.OPTIMIZED, BackgroundActivity.UNRESTRICTED -> "allow"
        }
        val appOps = shell.exec(
            listOf("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", appOpsMode)
        )
        if (!appOps.isSuccess) return ApplyResult.Failed("appops", appOps.stderr, applied.toList())
        applied += "appops"

        val sign = if (policy.backgroundActivity == BackgroundActivity.UNRESTRICTED) "+" else "-"
        val whitelist = shell.exec(listOf("dumpsys", "deviceidle", "whitelist", "$sign$pkg"))
        if (!whitelist.isSuccess) return ApplyResult.Failed("battery", whitelist.stderr, applied.toList())
        applied += "battery"

        // uid resolved now, not read from InstalledApp.uid — see the class doc above.
        // Resolved even when restrictBackgroundData is false, because the "remove"
        // command below still needs a uid to target; a package that vanished mid
        // operation is reported as a data-lever failure rather than silently skipped.
        val uid = apps.uidOf(pkg)
            ?: return ApplyResult.Failed("data", "package not installed", applied.toList())

        val verb = if (policy.restrictBackgroundData) "add" else "remove"
        val netpolicy = shell.exec(
            listOf("cmd", "netpolicy", verb, "restrict-background-blacklist", uid.toString())
        )
        if (!netpolicy.isSuccess) return ApplyResult.Failed("data", netpolicy.stderr, applied.toList())
        applied += "data"

        return ApplyResult.Success
    }
}
