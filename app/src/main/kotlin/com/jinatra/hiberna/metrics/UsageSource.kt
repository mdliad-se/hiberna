// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.metrics

/**
 * Foreground time per package over a window.
 *
 * A seam for the same reason [com.jinatra.hiberna.shell.ShellBackend] is one:
 * `UsageStatsManager` needs a real device and a granted appop, and the policy
 * and UI layers must stay testable without either.
 */
interface UsageSource {
    /**
     * Whether the `GET_USAGE_STATS` appop is granted to this app. False is a
     * normal state, not an error: the user can decline the one-time prompt and
     * keep using every other part of the app.
     */
    suspend fun hasAccess(): Boolean

    /**
     * Foreground milliseconds per package between [startMillis] and
     * [endMillis], or null when the data could not be read at all.
     *
     * Null is not an empty map. "We could not ask" and "no app was used" are
     * different answers, and collapsing them would show a device that has been
     * in someone's hand all morning as completely idle.
     */
    suspend fun foregroundMillis(startMillis: Long, endMillis: Long): Map<String, Long>?
}
