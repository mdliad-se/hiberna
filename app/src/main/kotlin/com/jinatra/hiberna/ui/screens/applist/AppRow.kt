// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.ui.components.BrutalButton
import com.jinatra.hiberna.ui.components.brutalSurface
import com.jinatra.hiberna.ui.theme.InkColor
import com.jinatra.hiberna.ui.theme.Mist
import com.jinatra.hiberna.ui.theme.Paper
import com.jinatra.hiberna.ui.theme.ShadowSm
import com.jinatra.hiberna.ui.theme.Teal

/**
 * One row: label, package, an optional sensitivity flag, and the tri-state
 * activity picker - this is the "editable" half of "every installed app in
 * one list, with its real background state, editable". [onActivityChange]
 * is fired only when a *different* state than [AppRowState.activity] is
 * tapped; the currently-active option renders disabled rather than calling
 * back with a no-op change.
 *
 * The sensitivity flag deliberately does NOT use [com.jinatra.hiberna.ui.theme.Signal]:
 * that colour is reserved for one highlight per screen (see Tokens.kt), and
 * sensitivity is a per-row, potentially-many-matches property - reusing
 * Signal here would multiply that highlight across a screen that can also
 * show a Signal error banner. [Mist] plus the always-drawn Ink border from
 * [brutalSurface] keeps the flag legible without spending that budget.
 *
 * Icons are loaded per-row by the UI layer rather than held on [InstalledApp]
 * - see that class's doc - but are not added here yet: no test in this task
 * calls for one, and a `Drawable` fetched per row still needs a safe
 * degrade-to-box path before it belongs in a real device screen.
 */
@Composable
fun AppRow(
    row: AppRowState,
    onClick: () -> Unit,
    onActivityChange: (BackgroundActivity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .brutalSurface(fill = if (row.app.isSystem) Mist else Paper, shadow = ShadowSm)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(0.dp).weight(1f)) {
            Text(text = row.app.label, style = MaterialTheme.typography.titleLarge)
            Text(
                text = row.app.packageName,
                style = MaterialTheme.typography.labelSmall,
            )
            if (row.sensitivity == Sensitivity.LIKELY_BREAKS) {
                Text(
                    text = "May stop working if restricted",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .brutalSurface(fill = Mist, shadow = 0.dp)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        ActivityPicker(current = row.activity, onSelect = onActivityChange)
    }
}

@Composable
private fun ActivityPicker(
    current: BackgroundActivity,
    onSelect: (BackgroundActivity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BackgroundActivity.entries.forEach { option ->
            val active = option == current
            BrutalButton(
                text = label(option),
                onClick = { onSelect(option) },
                fill = if (active) Teal else Paper,
                contentColor = if (active) Paper else InkColor,
                // The currently-active state cannot be tapped again: there is
                // nothing to change, so it must not read as a live control.
                enabled = !active,
            )
        }
    }
}

private fun label(activity: BackgroundActivity): String = when (activity) {
    BackgroundActivity.RESTRICTED -> "RESTRICTED"
    BackgroundActivity.OPTIMIZED -> "OPTIMIZED"
    BackgroundActivity.UNRESTRICTED -> "UNRESTRICTED"
}
