// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

import com.jinatra.hiberna.shell.ShellBackend

/**
 * Reads all three levers through the injected [ShellBackend] and assembles
 * one [CurrentPolicy] snapshot. Any lever that fails to read fails the whole
 * read: an empty snapshot and a failed read must never be the same value, or
 * the UI would report "nothing restricted" when the truth is "we could not
 * ask".
 */
class PolicyReader(private val shell: ShellBackend) {

    suspend fun read(): Result<CurrentPolicy> {
        val appOps = shell.exec(listOf("cmd", "appops", "query-op", "RUN_ANY_IN_BACKGROUND", "ignore"))
        if (!appOps.isSuccess) return Result.failure(ShellReadException("appops", appOps.stderr))

        val whitelist = shell.exec(listOf("dumpsys", "deviceidle", "whitelist"))
        if (!whitelist.isSuccess) return Result.failure(ShellReadException("deviceidle", whitelist.stderr))

        val batteryWhitelisted = parseDeviceIdleWhitelist(whitelist.stdout)
        if (batteryWhitelisted.isEmpty()) {
            // The spike confirmed this list is never empty on a real device:
            // every build ships system packages whitelisted. An empty result
            // here means the command failed silently, not that nothing is
            // whitelisted - an empty snapshot and a failed read must never be
            // the same value.
            return Result.failure(
                ShellReadException(
                    "deviceidle",
                    "whitelist came back empty; on a real device this list is never empty, " +
                        "so an empty result means the command failed rather than that nothing is whitelisted",
                )
            )
        }

        val netpolicy = shell.exec(listOf("cmd", "netpolicy", "list", "restrict-background-blacklist"))
        if (!netpolicy.isSuccess) return Result.failure(ShellReadException("netpolicy", netpolicy.stderr))

        return Result.success(
            CurrentPolicy(
                appOpsRestricted = parseAppOpsRestricted(appOps.stdout),
                batteryWhitelisted = batteryWhitelisted,
                dataBlockedUids = parseNetPolicyBlacklist(netpolicy.stdout),
            )
        )
    }
}

class ShellReadException(lever: String, stderr: String) :
    Exception("failed to read $lever state: $stderr")
