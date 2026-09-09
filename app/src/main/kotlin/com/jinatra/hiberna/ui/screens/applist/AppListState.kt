// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.metrics.AppMetric
import com.jinatra.hiberna.metrics.MetricsWindow
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.severity.Severity
import com.jinatra.hiberna.severity.severityOf

data class AppRowState(
    val app: InstalledApp,
    val activity: BackgroundActivity,
    val dataBlocked: Boolean,
    val sensitivity: Sensitivity,
    /**
     * H2: does this package declare a `dataSync`/`connectedDevice`/`specialUse`
     * foreground service type - see
     * [com.jinatra.hiberna.guardrail.SensitivityDetector.declaresExemptingForegroundServiceType].
     * Defaults `false` so every pre-existing call site (none of which cares
     * about this signal) keeps behaving exactly as before.
     */
    val hasExemptingForegroundServiceType: Boolean = false,
    /**
     * Battery and runtime for this package, or null when nothing was measured
     * at all. Both fields inside it are independently nullable too: declining
     * usage access costs runtime and not battery, and a moved `batterystats`
     * format costs battery and not runtime.
     */
    val metric: AppMetric? = null,
) {
    /**
     * The four-tier severity scale (see [severityOf]), derived from fields
     * this row already carries - never stored separately, so it can never
     * drift out of sync with [app], [activity], [sensitivity] or
     * [hasExemptingForegroundServiceType].
     */
    val severity: Severity
        get() = severityOf(
            sensitivity = sensitivity,
            isSystem = app.isSystem,
            activity = activity,
            hasExemptingForegroundServiceType = hasExemptingForegroundServiceType,
        )
}

/**
 * Which slice of the list to show. Answers "what have I already changed",
 * which the 1.0.0 list could not: the only ways to narrow it were the search
 * field and the system-apps switch.
 *
 * [DATA_BLOCKED] sits alongside the three background-activity states rather
 * than among them, because background data is an independent lever - an app
 * can be [BackgroundActivity.UNRESTRICTED] and still have its background data
 * blocked, so this is deliberately not a fourth activity state.
 */
enum class StateFilter(val label: String) {
    ALL("All"),
    RESTRICTED("Restricted"),
    OPTIMIZED("Optimized"),
    UNRESTRICTED("Unrestricted"),
    DATA_BLOCKED("Data blocked"),
    ;

    /** Whether [row] belongs in this slice. */
    fun matches(row: AppRowState): Boolean = when (this) {
        ALL -> true
        RESTRICTED -> row.activity == BackgroundActivity.RESTRICTED
        OPTIMIZED -> row.activity == BackgroundActivity.OPTIMIZED
        UNRESTRICTED -> row.activity == BackgroundActivity.UNRESTRICTED
        DATA_BLOCKED -> row.dataBlocked
    }
}

/**
 * Row order. [SEVERITY] stays the default: the payoff of the severity scale is
 * answering "where do I start", and nothing should regress for someone who
 * relies on that order.
 */
enum class SortBy(val label: String) {
    SEVERITY("Severity"),
    BATTERY_DESC("Battery"),
    RUNTIME_DESC("Runtime"),
}

data class AppListState(
    val rows: List<AppRowState> = emptyList(),
    val query: String = "",
    val showSystem: Boolean = false,
    val stateFilter: StateFilter = StateFilter.ALL,
    val sortBy: SortBy = SortBy.SEVERITY,
    /**
     * How many apps each filter would show. Counted after the system-apps
     * switch but *before* the search query, so a chip's number describes the
     * device rather than the current search - a count that moves while you
     * type cannot be read. Always holds an entry for every [StateFilter].
     */
    val stateCounts: Map<StateFilter, Int> = StateFilter.entries.associateWith { 0 },
    /**
     * The window both metrics cover, for the UI to state outright. A number
     * without its window is unreadable: ten minutes after unplugging, every
     * app looks clean.
     */
    val metricsWindow: MetricsWindow? = null,
    /**
     * True when the `GET_USAGE_STATS` appop is not granted, so the screen can
     * offer the one-time prompt rather than showing runtime as merely absent.
     */
    val needsUsageAccess: Boolean = false,
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
