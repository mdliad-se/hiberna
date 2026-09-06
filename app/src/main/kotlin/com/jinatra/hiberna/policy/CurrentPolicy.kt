// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

/**
 * A snapshot of what the system currently reports, across all three levers.
 * Package-keyed for the first two; uid-keyed for data, because that is what
 * netpolicy speaks.
 */
data class CurrentPolicy(
    val appOpsRestricted: Set<String>,
    val batteryWhitelisted: Set<String>,
    val dataBlockedUids: Set<Int>,
) {
    // The battery whitelist wins over the appops flag: an app can hold both,
    // and the whitelist is the stronger grant. Getting this backwards would
    // show RESTRICTED for an app that is in fact running freely.
    fun backgroundActivityFor(packageName: String): BackgroundActivity = when {
        packageName in batteryWhitelisted -> BackgroundActivity.UNRESTRICTED
        packageName in appOpsRestricted -> BackgroundActivity.RESTRICTED
        else -> BackgroundActivity.OPTIMIZED
    }

    fun isDataBlocked(uid: Int): Boolean = uid in dataBlockedUids

    companion object {
        val EMPTY = CurrentPolicy(emptySet(), emptySet(), emptySet())
    }
}
