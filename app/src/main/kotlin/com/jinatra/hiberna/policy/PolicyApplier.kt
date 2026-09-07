// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

import com.jinatra.hiberna.apps.InstalledAppRepository
import com.jinatra.hiberna.shell.ShellBackend
import com.jinatra.hiberna.shell.ShellResult

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
 *
 * **`cmd netpolicy remove` is not idempotent (device-confirmed, API 36):**
 * `cmd netpolicy remove restrict-background-blacklist <uid>` exits 255 with
 * `Error: UID <uid> not blacklisted` when the uid is already absent from the
 * blacklist — which is the normal starting state for nearly every app, since
 * `add` (confirmed idempotent on the same device) is the only lever most
 * users ever touch first. A desired state of "data not restricted" already
 * holds in that case, so [isAlreadyUnblacklisted] treats that *specific*
 * rejection as success rather than a failure — never a blanket "any
 * `netpolicy` non-zero exit is fine", which would swallow a genuine failure
 * (e.g. a permission denial) the same way. Checking blacklist membership
 * first (a `list` + parse) was the other option considered; the exit-code
 * check was chosen because it needs no second shell round-trip and matches
 * this class's existing shape of reading the one signal the command already
 * gives back, the same way every other lever here does.
 *
 * **Failure reasons prefer stderr, fall back to stdout:** `cmd netpolicy`
 * (device-confirmed) prints its `Error: ...` text to stdout, not stderr, so a
 * reason built from stderr alone renders empty. [ShellResult.reasonText]
 * applies the same stderr-first-else-stdout fallback to every lever here,
 * not just the one confirmed on-device, since nothing in this class's own
 * contract with [ShellBackend] promises any of them write errors to stderr.
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
        if (!appOps.isSuccess) return ApplyResult.Failed("appops", appOps.reasonText(), applied.toList())
        applied += "appops"

        val sign = if (policy.backgroundActivity == BackgroundActivity.UNRESTRICTED) "+" else "-"
        val whitelist = shell.exec(listOf("dumpsys", "deviceidle", "whitelist", "$sign$pkg"))
        if (!whitelist.isSuccess) return ApplyResult.Failed("battery", whitelist.reasonText(), applied.toList())
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
        val netpolicySucceeded = netpolicy.isSuccess ||
            (verb == "remove" && netpolicy.isAlreadyUnblacklisted())
        if (!netpolicySucceeded) return ApplyResult.Failed("data", netpolicy.reasonText(), applied.toList())
        applied += "data"

        return ApplyResult.Success
    }

    /**
     * True only for the exact device-confirmed rejection `cmd netpolicy
     * remove` gives when the uid was never blacklisted in the first place -
     * never for any other non-zero exit, which must still fail. See the
     * class doc above for why this counts as the desired state already
     * holding, not a masked failure.
     */
    private fun ShellResult.isAlreadyUnblacklisted(): Boolean =
        reasonText().contains("not blacklisted", ignoreCase = true)

    /**
     * `stderr`, unless it is blank - some commands (`cmd netpolicy`,
     * device-confirmed) print their `Error: ...` text to stdout instead. See
     * the class doc above.
     */
    private fun ShellResult.reasonText(): String = stderr.ifBlank { stdout }
}
