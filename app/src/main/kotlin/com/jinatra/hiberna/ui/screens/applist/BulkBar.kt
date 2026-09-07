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

// The one control on this screen allowed a 10.dp shadow: bulk apply is the
// reason hiberna exists, so it gets the hero treatment brand v1.1 reserves
// for exactly one element per view. Every BrutalButton below passes
// shadow = 0.dp on purpose: this bar is already a slab, and brand v1.1 says
// a slab inside a slab drops its shadow and keeps just the border.
//
// F2 fix: skippedCountFor is a function of the preset, not one shared Int.
// PresetScreen lets each preset skipSensitive toggle independently, so a
// single number borrowed from one preset could preview a decision a
// different preset button does not honour. Each preset with a positive
// count gets its own named line above the (still horizontal) button row.
//
// The buttons stay in one Row rather than one Column per preset: an
// earlier draft stacked three full-width buttons vertically, tripling this
// bar height, and on a caller using a small default test window that
// pushed the Cancel button below the simulated screen edge - the click
// silently missed with no exception. Named text lines above one row keep
// the height unchanged in the common (nothing skipped) case.
@Composable
fun BulkBar(
    selectedCount: Int,
    presets: List<Preset>,
    onApply: (Preset) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    skippedCountFor: (Preset) -> Int = { 0 },
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

        presets.forEach { preset ->
            val skipped = skippedCountFor(preset)
            if (skipped > 0) {
                Text(
                    text = "${preset.name}: $skipped will be left alone - restricting them " +
                        "may stop notifications or alarms. Open an app to override it.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
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
