// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.preset.Preset
import com.jinatra.hiberna.ui.components.BrutalButton
import com.jinatra.hiberna.ui.components.brutalSurface
import com.jinatra.hiberna.ui.theme.InkColor
import com.jinatra.hiberna.ui.theme.Paper
import com.jinatra.hiberna.ui.theme.ShadowLg
import com.jinatra.hiberna.ui.theme.Teal

/**
 * The one control on this screen allowed a 10.dp shadow: bulk apply is both
 * the reason hiberna exists and the single most consequential tap in the
 * app, so it gets the hero treatment brand v1.1 reserves for exactly one
 * element per view.
 *
 * Every [BrutalButton] below is passed `shadow = 0.dp` on purpose: this bar
 * is already a slab (its own [brutalSurface] with [ShadowLg]), and brand
 * v1.1 is explicit that "a slab inside a slab drops its shadow entirely and
 * keeps just the border" - a nested shadow here would land on this bar's own
 * border rather than reading as a separate control, exactly the layering
 * [com.jinatra.hiberna.ui.screens.applist.AppRow]'s `ActivityPicker` already
 * avoids for the same reason.
 *
 * The preset buttons use [Teal], never [com.jinatra.hiberna.ui.theme.Product]:
 * they are this screen's primary action - the whole reason this bar exists -
 * and brand v1.1 is explicit that Product never replaces Teal for a primary
 * action; Product is reserved for this app's own accent surfaces elsewhere.
 *
 * [skippedCount] is surfaced only when positive: a "0 apps will be left
 * alone" line would be silence dressed up as information. When it is
 * positive, the guardrail explains *why* those apps are being left out
 * (notifications, alarms) rather than just reporting a bare number - see the
 * task report for the fuller reasoning on what a user actually needs to see
 * around a bulk apply.
 */
@Composable
fun BulkBar(
    selectedCount: Int,
    skippedCount: Int,
    presets: List<Preset>,
    onApply: (Preset) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .brutalSurface(fill = Paper, shadow = ShadowLg)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "$selectedCount selected",
            style = MaterialTheme.typography.titleLarge,
        )

        if (skippedCount > 0) {
            Text(
                text = "$skippedCount will be left alone - restricting them may stop " +
                    "notifications or alarms. Open an app to override it.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            presets.forEach { preset ->
                BrutalButton(
                    text = preset.name,
                    onClick = { onApply(preset) },
                    fill = Teal,
                    shadow = 0.dp,
                )
            }
        }

        BrutalButton(
            text = "Cancel",
            onClick = onClear,
            fill = Paper,
            contentColor = InkColor,
            shadow = 0.dp,
        )
    }
}
