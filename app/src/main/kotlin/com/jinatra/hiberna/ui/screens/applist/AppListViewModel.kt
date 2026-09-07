// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jinatra.hiberna.apps.InstalledAppRepository
import com.jinatra.hiberna.guardrail.SensitivityDetector
import com.jinatra.hiberna.policy.AppPolicy
import com.jinatra.hiberna.policy.ApplyResult
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.policy.BulkApplier
import com.jinatra.hiberna.policy.BulkOutcome
import com.jinatra.hiberna.policy.BulkTarget
import com.jinatra.hiberna.policy.PolicyApplier
import com.jinatra.hiberna.policy.PolicyReader
import com.jinatra.hiberna.policy.isSkippedByGuardrail
import com.jinatra.hiberna.preset.OverrideRepository
import com.jinatra.hiberna.preset.Preset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Backs the app list - the screen the app exists for. Every installed app,
 * with its real system state, and (for background activity) editable in
 * place, plus (Task 12) multi-select and a guarded bulk apply across the
 * current selection.
 */
class AppListViewModel(
    private val apps: InstalledAppRepository,
    private val reader: PolicyReader,
    private val applier: PolicyApplier,
    private val sensitivity: SensitivityDetector,
    private val bulk: BulkApplier,
    private val overrides: OverrideRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AppListState())
    val state: StateFlow<AppListState> = _state.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

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

    fun toggleSelection(packageName: String) {
        val current = _selected.value
        _selected.value =
            if (packageName in current) current - packageName else current + packageName
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /**
     * How many of the *currently selected* packages [preset] would refuse to
     * touch, per the guardrail - for [BulkBar]'s preview text, shown before
     * the user commits to a preset. This calls the exact same
     * [isSkippedByGuardrail] predicate [BulkApplier.apply] uses to actually
     * decide, rather than re-deriving "is this app sensitive and not
     * overridden" here - see that function's doc for why a second copy of
     * this decision would risk drifting from what apply time actually does.
     */
    fun skippedCount(preset: Preset, overridden: Set<String>): Int {
        val targetPackages = _selected.value
        return all.count { row ->
            row.app.packageName in targetPackages &&
                BulkTarget(row.app.packageName, row.sensitivity).isSkippedByGuardrail(preset, overridden)
        }
    }

    fun dismissBulkSummary() {
        _state.value = _state.value.copy(bulkSummary = null)
    }

    /**
     * Applies [preset] across every currently selected package, then clears
     * the selection and reports what actually happened.
     *
     * The guardrail lives in [BulkApplier], not here: this only maps rows to
     * [BulkTarget]s (never the UI's [AppRowState] itself - see [BulkTarget]'s
     * own doc for why that boundary matters) and reads the persisted
     * per-package overrides at the moment of apply, not from some cached
     * snapshot that could be stale by the time the user taps a preset.
     *
     * After the write, only the packages [BulkOutcome] says were actually
     * touched - [BulkOutcome.applied] and [BulkOutcome.failed], never
     * [BulkOutcome.skipped] - are re-read via [PolicyReader.read] and folded
     * back into [all], for the same honesty reason [applyAndReflect] re-reads
     * a single row instead of trusting the write: three shell exit codes are
     * not proof the system now matches what was asked for. This deliberately
     * does *not* call [load] the way the task brief originally sketched:
     * [load] also re-enumerates every installed package through
     * `PackageManager`, work a policy toggle - bulk or single - can never
     * invalidate, since toggling a lever does not install or remove apps. A
     * bulk apply over a few hundred selected packages would otherwise cost a
     * few-hundred-app `PackageManager` rescan on top of the one cheap
     * [PolicyReader.read] that is actually needed. See the task report for
     * the fuller reasoning, including why the summary text (rather than a
     * toast or a modal) is what actually gets shown for a partial failure.
     */
    suspend fun applyPreset(preset: Preset) {
        val overridden = overrides.overridden.first()
        val targetPackages = _selected.value
        val targets = all
            .filter { it.app.packageName in targetPackages }
            .map { BulkTarget(it.app.packageName, it.sensitivity) }

        val outcome = bulk.apply(targets, preset, overridden)
        clearSelection()
        reflectBulk(outcome)
        _state.value = _state.value.copy(bulkSummary = bulkSummaryFor(outcome).ifBlank { null })
    }

    /**
     * Re-reads real system state for exactly the packages a bulk apply
     * touched - [BulkOutcome.applied] landed, [BulkOutcome.failed] may have
     * partially landed (see [ApplyResult.Failed.applied]) - and folds the
     * confirmed values back into [all]. Skipped packages are left alone: the
     * guardrail never attempted a write for them, so there is nothing new to
     * confirm.
     */
    private suspend fun reflectBulk(outcome: BulkOutcome) {
        val touched = (outcome.applied + outcome.failed.keys).toSet()
        if (touched.isEmpty()) return

        val policy = reader.read().getOrElse { t ->
            _state.value = _state.value.copy(error = message(t))
            return
        }
        all = all.map { row ->
            if (row.app.packageName !in touched) return@map row
            val freshUid = apps.uidOf(row.app.packageName)
            row.copy(
                activity = policy.backgroundActivityFor(row.app.packageName),
                dataBlocked = freshUid?.let(policy::isDataBlocked) ?: row.dataBlocked,
            )
        }
        reproject()
    }

    /**
     * One line, not a toast and not a modal: a toast naming nothing is
     * useless the moment it disappears, and a modal listing forty package
     * names is worse - the user did not ask for a report, they asked "did
     * this work". So this names counts for what worked and what the
     * guardrail deliberately left alone (both harmless to summarise in bulk),
     * and names actual apps - by label, not raw package name, capped at
     * [MAX_NAMED_FAILURES] - only for the case that is actually unexpected:
     * a failure. See the task report for the fuller reasoning.
     */
    private fun bulkSummaryFor(outcome: BulkOutcome): String {
        val parts = mutableListOf<String>()
        if (outcome.applied.isNotEmpty()) {
            val n = outcome.applied.size
            parts += "Changed $n app${if (n == 1) "" else "s"}."
        }
        if (outcome.skipped.isNotEmpty()) {
            val n = outcome.skipped.size
            parts += "Left $n alone - restricting them may stop notifications or alarms. " +
                "Open one to override it."
        }
        if (outcome.failed.isNotEmpty()) {
            val named = outcome.failed.entries.take(MAX_NAMED_FAILURES).joinToString { (pkg, failure) ->
                // A package can be "failed" and partially changed at the same
                // time - there is no rollback (see BulkFailure's doc) - so a
                // failure whose earlier levers already landed is described as
                // partial, not as an outright, nothing-happened failure.
                val already = if (failure.applied.isNotEmpty()) {
                    " - already changed ${failure.applied.joinToString()}"
                } else {
                    ""
                }
                "${labelFor(pkg)} (${failure.lever}: ${failure.reason}$already)"
            }
            val more = outcome.failed.size - MAX_NAMED_FAILURES
            val suffix = if (more > 0) " and $more more" else ""
            parts += "Could not change: $named$suffix."
        }
        return parts.joinToString(" ")
    }

    private fun labelFor(packageName: String): String =
        all.firstOrNull { it.app.packageName == packageName }?.app?.label ?: packageName

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
                        error = reVerifyFailureMessage(desired.app.label, message(t)),
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
                _state.value = _state.value.copy(
                    // ApplyResult.Success only proves the netpolicy write may
                    // have landed - it is not proof this row's dataBlocked
                    // value above was actually re-confirmed. A null uid here
                    // means exactly one part of the result (the data lever)
                    // could not be checked; that must surface as an error of
                    // its own; falling back to `previous.dataBlocked` while
                    // reporting no error would let a stale value ride under
                    // a false "everything is fine", which is the exact drift
                    // the honesty rule exists to prevent.
                    error = if (freshUid == null) {
                        partialReVerifyFailureMessage(desired.app.label)
                    } else {
                        null
                    },
                )
                reproject()
            }

            is ApplyResult.Failed -> {
                _state.value = _state.value.copy(error = applyFailureMessage(desired.app.label, result))
            }
        }
    }

    private fun message(t: Throwable): String = t.message ?: "could not read system state"

    /**
     * Brand voice leads with the fix, not the failure - see [GateScreen][com.jinatra.hiberna.ui.screens.gate.GateScreen]'s
     * "Install Shizuku from F-Droid... then come back here" for the pattern
     * this follows. The app label is used in place of the raw package name
     * for the user-facing part; raw shell/lever detail stays available, just
     * secondary, in parentheses at the end.
     */
    private fun applyFailureMessage(label: String, result: ApplyResult.Failed): String {
        val partial = if (result.applied.isNotEmpty()) {
            " hiberna already changed: ${result.applied.joinToString()}."
        } else {
            ""
        }
        return "hiberna could not change $label. Shizuku may have stopped - check it is " +
            "running, then try again.$partial (${result.lever}: ${result.reason})"
    }

    private fun reVerifyFailureMessage(label: String, reason: String): String =
        "hiberna changed $label but could not confirm the new state. Shizuku may have " +
            "stopped - check it is running, then try again. ($reason)"

    private fun partialReVerifyFailureMessage(label: String): String =
        "hiberna changed $label but could not confirm its data-blocking state. Shizuku may " +
            "have stopped - check it is running, then try again."

    private fun reproject() {
        val current = _state.value
        val needle = current.query.trim().lowercase()
        _state.value = current.copy(
            rows = all
                .filter { current.showSystem || !it.app.isSystem }
                .filter { needle.isEmpty() || it.app.label.lowercase().contains(needle) }
                // Recommended first - the whole payoff of the severity scale
                // is answering "where do I start" (see the task brief), so
                // this sort is not cosmetic. Tiebreak alphabetical by label,
                // the list's pre-existing order, preserved within a tier.
                .sortedWith(compareBy({ it.severity.ordinal }, { it.app.label.lowercase() })),
        )
    }

    private companion object {
        /** Named failures shown in [bulkSummaryFor] before collapsing to "and N more". */
        const val MAX_NAMED_FAILURES = 3
    }
}
