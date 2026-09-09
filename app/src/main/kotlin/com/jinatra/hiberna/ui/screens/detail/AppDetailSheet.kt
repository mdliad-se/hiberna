// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.guardrail.isSensitive
import com.jinatra.hiberna.metrics.MetricFormat
import com.jinatra.hiberna.metrics.MetricsWindow
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.ui.components.ActivityPicker
import com.jinatra.hiberna.ui.components.BrutalButton
import com.jinatra.hiberna.ui.components.BrutalTopBar
import com.jinatra.hiberna.ui.components.brutalSurface
import com.jinatra.hiberna.ui.screens.applist.AppRowState
import com.jinatra.hiberna.ui.theme.InkColor
import com.jinatra.hiberna.ui.theme.Mist
import com.jinatra.hiberna.ui.theme.Paper
import com.jinatra.hiberna.ui.theme.ShadowMd

/**
 * The per-app drill-down Task 11's row taps into (Task 14 wires the actual
 * navigation). Full package identity, the same tri-state
 * [com.jinatra.hiberna.ui.components.ActivityPicker] `AppRow` already uses -
 * never a second copy of the same setting, since that would risk the two
 * drifting apart the same way a reviewer already flagged for the guardrail
 * predicate this task's neighbour introduced (see
 * `AppListViewModel.skippedCount`'s doc) - a background-data switch, and, for
 * a [Sensitivity.isSensitive] app, the one place in the app a user learns
 * *why* it is flagged (or, for [Sensitivity.UNKNOWN], that hiberna could not
 * check) before choosing to override the guardrail.
 *
 * [overridden] is passed in rather than derived from [Sensitivity]: overrides
 * are per-package decisions persisted in `OverrideRepository`, not a state the
 * three-value [Sensitivity] enum encodes - see that repository's doc. This
 * composable only ever reflects the caller's value and reports a toggle via
 * [onOverrideChange]; it never reads or writes the repository itself.
 *
 * The explanation names the actual consequence - silenced notifications,
 * alarms that do not fire - rather than a generic "may break" caution, for a
 * [Sensitivity.LIKELY_BREAKS] row. A user deciding whether to override a
 * guardrail needs to know what they are risking, stated as a fact, not
 * hedged with "might" or "may"; this is the one place in the app that
 * consequence is ever spelled out, so a vague caution here would leave the
 * user no better informed than the row's own "May stop working if
 * restricted" chip already did. A [Sensitivity.UNKNOWN] row gets its own,
 * different copy - see the `when` below - rather than reusing that sentence:
 * hiberna never ran a successful check on that package, so claiming it
 * "handles messaging, alarms, calls or sign-in" would assert something that
 * did not happen. This mirrors `AppRow`'s own "Couldn't check this app" vs
 * "Caution" split for the identical reason (see that file's doc). A review
 * finding (B1) caught the previous version of this file gating on
 * `== LIKELY_BREAKS` alone: that left an [Sensitivity.UNKNOWN] app with no
 * explanation box and, critically, no override switch at all - a dead end,
 * since bulk apply already skips [Sensitivity.UNKNOWN] the same as a
 * confirmed hit (see [com.jinatra.hiberna.policy.isSkippedByGuardrail]) but
 * this was the only place such a package could ever be individually
 * overridden into a bulk apply.
 *
 * The explanation box (and the override switch inside it) render only for a
 * [Sensitivity.isSensitive] app: showing "why is this flagged" copy for a row
 * that isn't sensitive at all would just be confusing noise, and there is
 * nothing to override for such a row anyway.
 *
 * "Open in Settings" only ever fires [onOpenSettings] - this composable never
 * builds an `Intent` itself. `ACTION_APPLICATION_DETAILS_SETTINGS` is a
 * plain, unprivileged Intent, so it must never be routed through
 * `ShellBackend`: only `shell`/`privilege` code may touch Shizuku or a shell,
 * and this is neither. The hosting Activity owns the Intent instead,
 * following the same shape as `MainActivity.openShizukuInstallPage` -
 * building the Intent there and catching `ActivityNotFoundException` so a
 * device that cannot handle it does not crash - which Task 14 wires up.
 *
 * Every control here sits inside this composable's own [brutalSurface] slab,
 * so - brand v1.1: "a slab inside a slab drops its shadow entirely and keeps
 * just the border" - each carries `shadow = 0.dp` of its own, the same
 * convention `AppRow`'s picker and `BulkBar`'s buttons already follow. The
 * [BrutalTopBar] below passes `nestedInSlab = true` for the same reason - a
 * review finding: it previously hardcoded its own back/close button's shadow
 * to zero unconditionally, which happened to be correct here but was wrong
 * on `PresetScreen`, whose bar is not nested in any slab.
 */
@Composable
fun AppDetailSheet(
    row: AppRowState,
    overridden: Boolean,
    onActivityChange: (BackgroundActivity) -> Unit,
    onDataChange: (Boolean) -> Unit,
    onOverrideChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit = {},
    /**
     * The window [row]'s metrics cover. Null renders as "no measurement
     * window" rather than being omitted: a figure with no window is
     * unreadable, since everything looks small right after a charge.
     */
    metricsWindow: MetricsWindow? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .brutalSurface(fill = Paper, shadow = ShadowMd)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrutalTopBar(title = row.app.label, onBack = onClose, backLabel = "Close", nestedInSlab = true)
        Text(text = row.app.packageName, style = MaterialTheme.typography.labelSmall)

        // The full metric breakdown. The list row shows the same two numbers
        // compressed to one line; this is where they get their window, their
        // units, and the caveats that make them honest.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .brutalSurface(fill = Mist, shadow = 0.dp)
                .padding(16.dp)
                .testTag("metrics-detail"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "Battery and runtime", style = MaterialTheme.typography.titleLarge)
            Text(
                text = MetricFormat.window(metricsWindow),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = buildString {
                    append("Battery: ")
                    append(MetricFormat.battery(row.metric?.batteryPercent))
                    row.metric?.batteryMah?.let { append(" (%.2f mAh)".format(it)) }
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Runtime: ${MetricFormat.runtime(row.metric?.foregroundMillis)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (row.metric?.batteryIsSharedUid == true) {
                // batterystats attributes power to uids, not packages. Saying
                // nothing here would present a whole uid group's drain as
                // this one app's.
                Text(
                    text = "This app shares a user id with another installed app, so the " +
                        "battery figure covers both.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (row.metric?.batteryPercent != null) {
                Text(
                    text = "Battery use is Android's own estimate and may not match Settings.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (row.sensitivity.isSensitive) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .brutalSurface(fill = Mist, shadow = 0.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (row.sensitivity == Sensitivity.UNKNOWN) {
                        // Distinct from the LIKELY_BREAKS copy below on
                        // purpose - see AppRow's own doc for why the two
                        // must never share a sentence, and never the same
                        // specific claim ("handles messaging, alarms, calls
                        // or sign-in"). This one is an admission, not a
                        // judgment call: a detection query failed instead of
                        // answering, so hiberna does not get to describe what
                        // this app does - only that it could not check.
                        "hiberna could not check this app - a detection query failed instead " +
                            "of answering. Restricting it might be fine, or it might silently " +
                            "stop something you rely on; hiberna genuinely does not know either " +
                            "way."
                    } else {
                        "This app handles messaging, alarms, calls or sign-in in the " +
                            "background. Restricting it stops notifications from arriving and " +
                            "alarms from firing."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(
                    modifier = Modifier.toggleable(
                        value = overridden,
                        role = Role.Switch,
                        onValueChange = onOverrideChange,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // onCheckedChange = null: the enclosing Row's toggleable
                    // above is the single source of the click/tap target (a
                    // bigger, label-inclusive tap area), not the Switch
                    // itself - two overlapping click targets on one control
                    // would double-report the same toggle.
                    Switch(checked = overridden, onCheckedChange = null)
                    Text(
                        text = "Include it in bulk changes anyway",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        Text(text = "Background activity", style = MaterialTheme.typography.bodyLarge)
        ActivityPicker(
            current = row.activity,
            onSelect = onActivityChange,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.toggleable(
                value = row.dataBlocked,
                role = Role.Switch,
                onValueChange = onDataChange,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = row.dataBlocked, onCheckedChange = null)
            Text(
                text = "Block background mobile data",
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        BrutalButton(
            text = "Open in Settings",
            onClick = onOpenSettings,
            fill = Paper,
            contentColor = InkColor,
            shadow = 0.dp,
        )
    }
}
