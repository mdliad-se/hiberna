// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jinatra.hiberna.apps.InstalledAppRepository
import com.jinatra.hiberna.guardrail.SensitivityDetector
import com.jinatra.hiberna.policy.AppPolicy
import com.jinatra.hiberna.policy.ApplyResult
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.policy.PolicyApplier
import com.jinatra.hiberna.policy.PolicyReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the app list - the screen the app exists for. Every installed app,
 * with its real system state, and (for background activity) editable in
 * place.
 *
 * Two constructor parameters are coming in Task 12 (`bulk: BulkApplier`,
 * `overrides: OverrideRepository`) for multi-select bulk apply. Nothing here
 * should need to change shape for that - it is additive, not a rewrite.
 */
class AppListViewModel(
    private val apps: InstalledAppRepository,
    private val reader: PolicyReader,
    private val applier: PolicyApplier,
    private val sensitivity: SensitivityDetector,
) : ViewModel() {

    private val _state = MutableStateFlow(AppListState())
    val state: StateFlow<AppListState> = _state.asStateFlow()

    /**
     * The full, unfiltered snapshot [reproject] derives `state.rows` from.
     * Kept separate from [state] so a query or show-system change never has
     * to touch the shell/PackageManager layer to redraw.
     */
    private var all: List<AppRowState> = emptyList()

    suspend fun load() {
        _state.value = _state.value.copy(loading = true, error = null)

        val policy = reader.read().getOrElse { t ->
            // An empty snapshot and a failed read must never be the same
            // value - see PolicyReader's own doc for why. Clearing `all` too
            // means a stale successful load from before this failure can't
            // silently reappear through onQueryChange/onShowSystemChange's
            // reproject() before the next successful load. Query and
            // show-system are preserved, though: a failed refresh should not
            // wipe out what the user was searching for.
            all = emptyList()
            _state.value = _state.value.copy(loading = false, error = message(t), rows = emptyList())
            return
        }

        all = apps.load().map { app ->
            AppRowState(
                app = app,
                activity = policy.backgroundActivityFor(app.packageName),
                dataBlocked = policy.isDataBlocked(app.uid),
                sensitivity = sensitivity.classify(app.packageName),
            )
        }
        _state.value = _state.value.copy(loading = false, error = null)
        reproject()
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        reproject()
    }

    fun onShowSystemChange(show: Boolean) {
        _state.value = _state.value.copy(showSystem = show)
        reproject()
    }

    suspend fun setActivity(packageName: String, activity: BackgroundActivity) {
        val row = all.firstOrNull { it.app.packageName == packageName } ?: return
        applyAndReflect(row.copy(activity = activity))
    }

    suspend fun setDataBlocked(packageName: String, blocked: Boolean) {
        val row = all.firstOrNull { it.app.packageName == packageName } ?: return
        applyAndReflect(row.copy(dataBlocked = blocked))
    }

    /**
     * Applies [desired] and reflects the *real* outcome, never the requested
     * one - an optimistic UI here would lie about whether the phone actually
     * changed, and honesty about that is this app's entire value.
     *
     * On success, this does not just copy [desired] into the cache: that
     * would mean trusting three shell exit codes as proof the system now
     * matches what was asked for, which is exactly the shortcut the honesty
     * rule forbids. Instead it re-reads the three levers with
     * [PolicyReader.read] and recomputes this one row from the answer.
     * That read is cheap - three shell commands - compared to calling
     * [load] again, which would also re-enumerate every installed package
     * through `PackageManager`: work a policy toggle can never invalidate,
     * since toggling a lever does not install or remove apps. So a toggle
     * costs one extra shell round trip, not a full rescan of a possibly
     * 300-app device. See the task report for the fuller reasoning.
     *
     * On failure, the row is left exactly as it was: [ApplyResult.Failed]
     * already tells us the write did not fully land, and [ApplyResult.Failed.applied]
     * names any levers that DID land first, since there is no rollback.
     */
    private suspend fun applyAndReflect(desired: AppRowState) {
        val result = applier.apply(
            AppPolicy(
                packageName = desired.app.packageName,
                backgroundActivity = desired.activity,
                restrictBackgroundData = desired.dataBlocked,
            )
        )
        when (result) {
            is ApplyResult.Success -> {
                val policy = reader.read().getOrElse { t ->
                    _state.value = _state.value.copy(
                        error = "changed ${desired.app.packageName} but could not confirm the new state: " +
                            message(t),
                    )
                    return
                }
                val previous = all.firstOrNull { it.app.packageName == desired.app.packageName } ?: desired
                // Resolved fresh, not read from the cached InstalledApp.uid -
                // the uid PolicyApplier just wrote against may not be the one
                // this row was enumerated with. See InstalledApp.uid's doc.
                val freshUid = apps.uidOf(desired.app.packageName)
                val confirmed = desired.copy(
                    activity = policy.backgroundActivityFor(desired.app.packageName),
                    dataBlocked = freshUid?.let(policy::isDataBlocked) ?: previous.dataBlocked,
                )
                all = all.map { if (it.app.packageName == confirmed.app.packageName) confirmed else it }
                _state.value = _state.value.copy(error = null)
                reproject()
            }

            is ApplyResult.Failed -> {
                val partial = if (result.applied.isNotEmpty()) {
                    " (already changed: ${result.applied.joinToString()})"
                } else {
                    ""
                }
                _state.value = _state.value.copy(
                    error = "could not change ${result.lever} for ${desired.app.packageName}: " +
                        "${result.reason}$partial",
                )
            }
        }
    }

    private fun message(t: Throwable): String = t.message ?: "could not read system state"

    private fun reproject() {
        val current = _state.value
        val needle = current.query.trim().lowercase()
        _state.value = current.copy(
            rows = all
                .filter { current.showSystem || !it.app.isSystem }
                .filter { needle.isEmpty() || it.app.label.lowercase().contains(needle) },
        )
    }
}
