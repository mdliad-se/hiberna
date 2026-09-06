// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.policy.BackgroundActivity

data class AppRowState(
    val app: InstalledApp,
    val activity: BackgroundActivity,
    val dataBlocked: Boolean,
    val sensitivity: Sensitivity,
)

data class AppListState(
    val rows: List<AppRowState> = emptyList(),
    val query: String = "",
    val showSystem: Boolean = false,
    val loading: Boolean = false,
    /** Non-null means we could not read system state. Never conflate with "nothing restricted". */
    val error: String? = null,
)
