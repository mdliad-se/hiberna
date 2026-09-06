// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

/**
 * Outcome of applying an [AppPolicy] via [PolicyApplier].
 *
 * The three levers (appops, battery whitelist, data restriction) run in a
 * fixed sequence with no rollback. That means a failure partway through can
 * leave the device in a state that is neither "nothing changed" nor "policy
 * fully applied": e.g. appops landed, the battery whitelist write then
 * failed, and data restriction was never attempted. [Failed.applied] names
 * the levers that already landed before [Failed.lever] failed, so a caller
 * can tell the user which controls actually changed instead of reporting one
 * opaque failure that hides a partial mutation of their device.
 */
sealed interface ApplyResult {
    data object Success : ApplyResult

    data class Failed(
        val lever: String,
        val reason: String,
        val applied: List<String> = emptyList(),
    ) : ApplyResult
}
