// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.metrics

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Foreground time from `UsageStatsManager`.
 *
 * Computed from [UsageEvents], **not** `queryAndAggregateUsageStats`. The
 * aggregate API returns daily buckets whose totals extend before the window
 * start, so on a short window - and "since last charge" usually is one - every
 * app would be over-reported, sometimes by hours. Events are exact within the
 * window at the cost of pairing resumes with pauses by hand.
 */
class RealUsageSource(private val context: Context) : UsageSource {

    override suspend fun hasAccess(): Boolean = withContext(Dispatchers.IO) {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return@withContext false
        val mode = runCatching {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }.getOrElse { return@withContext false }
        mode == AppOpsManager.MODE_ALLOWED
    }

    override suspend fun foregroundMillis(startMillis: Long, endMillis: Long): Map<String, Long>? =
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(UsageStatsManager::class.java)
                ?: return@withContext null
            val events = runCatching { manager.queryEvents(startMillis, endMillis) }
                .getOrElse { return@withContext null }
                ?: return@withContext null

            val totals = mutableMapOf<String, Long>()
            // Last observed resume per package. A package can be resumed and
            // paused several times inside one window, and the events are not
            // guaranteed to interleave neatly across packages.
            val resumedAt = mutableMapOf<String, Long>()
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> resumedAt[pkg] = event.timeStamp

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    -> {
                        // A pause with no matching resume means the app was
                        // already in the foreground when the window opened, so
                        // it counts from the window start rather than being
                        // discarded - dropping it would under-report exactly
                        // the app the user was using when they unplugged.
                        val from = resumedAt.remove(pkg) ?: startMillis
                        val span = event.timeStamp - from
                        if (span > 0) totals[pkg] = (totals[pkg] ?: 0L) + span
                    }
                }
            }

            // Whatever is still resumed is in the foreground right now, so it
            // accrues up to the end of the window.
            for ((pkg, from) in resumedAt) {
                val span = endMillis - from
                if (span > 0) totals[pkg] = (totals[pkg] ?: 0L) + span
            }

            totals
        }
}
