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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.guardrail.Sensitivity
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
 * a [Sensitivity.LIKELY_BREAKS] app, the one place in the app a user learns
 * *why* it is flagged before choosing to override the guardrail.
 *
 * [overridden] is passed in rather than derived from [Sensitivity]: overrides
 * are per-package decisions persisted in `OverrideRepository`, not a state the
 * two-value [Sensitivity] enum encodes - see that repository's doc. This
 * composable only ever reflects the caller's value and reports a toggle via
 * [onOverrideChange]; it never reads or writes the repository itself.
 *
 * The explanation names the actual consequence - silenced notifications,
 * alarms that do not fire - rather than a generic "may break" caution. A user
 * deciding whether to override a guardrail needs to know what they are
 * risking, stated as a fact, not hedged with "might" or "may"; this is the
 * one place in the app that consequence is ever spelled out, so a vague
 * caution here would leave the user no better informed than the row's own
 * "May stop working if restricted" chip already did.
 *
 * The explanation box (and the override switch inside it) render only for a
 * flagged app: showing "why is this flagged" copy for a row that isn't
 * flagged would just be confusing noise, and there is nothing to override
 * for such a row anyway.
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

        if (row.sensitivity == Sensitivity.LIKELY_BREAKS) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .brutalSurface(fill = Mist, shadow = 0.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "This app handles messaging, alarms, calls or sign-in in the " +
                        "background. Restricting it stops notifications from arriving and " +
                        "alarms from firing.",
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
