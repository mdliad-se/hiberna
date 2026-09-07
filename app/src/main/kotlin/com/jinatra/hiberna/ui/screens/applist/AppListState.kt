// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.severity.Severity
import com.jinatra.hiberna.severity.severityOf

data class AppRowState(
    val app: InstalledApp,
    val activity: BackgroundActivity,
    val dataBlocked: Boolean,
    val sensitivity: Sensitivity,
) {
    /**
     * The four-tier severity scale (see [severityOf]), derived from fields
     * this row already carries - never stored separately, so it can never
     * drift out of sync with [app], [activity] or [sensitivity].
     */
    val severity: Severity
        get() = severityOf(sensitivity = sensitivity, isSystem = app.isSystem, activity = activity)
}

data class AppListState(
    val rows: List<AppRowState> = emptyList(),
    val query: String = "",
    val showSystem: Boolean = false,
    val loading: Boolean = false,
    /** Non-null means we could not read system state. Never conflate with "nothing restricted". */
    val error: String? = null,
    /**
     * A one-line, human-readable summary of the last bulk apply - see
     * [AppListViewModel.applyPreset]'s doc for why a toast or a modal listing
     * every touched package are both wrong here. Non-null until
     * [AppListViewModel.dismissBulkSummary] is called or another bulk apply
     * replaces it.
     */
    val bulkSummary: String? = null,
)
