// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.metrics

/**
 * Test double for [UsageSource]. Records the window it was asked about so a
 * test can assert the reader derived it from time-on-battery rather than
 * inventing one.
 */
class FakeUsageSource(
    private val access: Boolean = true,
    private val foreground: Map<String, Long>? = emptyMap(),
) : UsageSource {

    var lastStartMillis: Long? = null
        private set
    var lastEndMillis: Long? = null
        private set
    var queryCount = 0
        private set

    override suspend fun hasAccess(): Boolean = access

    override suspend fun foregroundMillis(startMillis: Long, endMillis: Long): Map<String, Long>? {
        queryCount++
        lastStartMillis = startMillis
        lastEndMillis = endMillis
        return foreground
    }
}
