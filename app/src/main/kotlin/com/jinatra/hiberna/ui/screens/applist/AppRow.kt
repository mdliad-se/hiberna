// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.ui.components.BrutalButton
import com.jinatra.hiberna.ui.components.brutalSurface
import com.jinatra.hiberna.ui.theme.Cream
import com.jinatra.hiberna.ui.theme.InkColor
import com.jinatra.hiberna.ui.theme.Mist
import com.jinatra.hiberna.ui.theme.Paper
import com.jinatra.hiberna.ui.theme.ShadowSm
import com.jinatra.hiberna.ui.theme.Teal

/**
 * One row: label, package, an optional sensitivity flag, and the tri-state
 * activity picker on its own row beneath them - this is the "editable" half
 * of "every installed app in one list, with its real background state,
 * editable". [onActivityChange] is fired only when a *different* state than
 * [AppRowState.activity] is tapped; the currently-active option is a no-op
 * tap rather than a redundant re-apply, surfaced to accessibility services as
 * "selected" (see [ActivityPicker]) rather than "disabled".
 *
 * The picker lives in its own full-width row below the label/package/chip
 * column, not beside it: three full-size [BrutalButton]s in a `Row` next to
 * a label `Column` on a real 360-411dp phone would squeeze that label to
 * nothing, and Robolectric's semantic tests cannot see that squeeze (they
 * don't measure pixels). Stacking instead, with every button carrying
 * `Modifier.weight(1f)`, gives the label its full width above and the three
 * buttons an even three-way split below.
 *
 * At 360dp the three-way split still leaves only ~48dp of text budget per
 * button under [BrutalButton]'s default 20.dp horizontal padding and
 * 16.dp gap - not enough for "UNRESTRICTED" without a mid-word wrap. Brand
 * v1.1 supplies the fix: "a slab inside a slab drops its shadow entirely
 * and keeps just the border" - these buttons sit inside [AppRow]'s own
 * slab, so they carry no shadow of their own (`shadow = 0.dp`). The
 * brand's minimum 16.dp gap exists only to keep one shadow off a
 * neighbour's border, so once these buttons are unshadowed that minimum no
 * longer applies and the gap can shrink to 8.dp. Combined with a tighter
 * [BrutalButton] `contentPadding` and the single-line, smaller-type label
 * below, the widest label now fits - see `AppListScreenTest`'s
 * `w360dp-h640dp` layout-measurement test, which asserts the actual laid
 * out text height rather than trusting arithmetic.
 *
 * The sensitivity flag deliberately does NOT use [com.jinatra.hiberna.ui.theme.Signal]:
 * that colour is reserved for one highlight per screen (see Tokens.kt), and
 * sensitivity is a per-row, potentially-many-matches property - reusing
 * Signal here would multiply that highlight across a screen that can also
 * show a Signal error banner. It also must not reuse [Mist]: a system row's
 * own background is filled with [Mist], so an app that is both sensitive
 * *and* a system app (visible as soon as `showSystem` is on) would render a
 * chip that is colour-identical to the row behind it, leaving only a 3px
 * border as the sole warning before a restriction that could break the app.
 * [Cream] - the brand canvas colour - reads clearly against both [Paper] and
 * [Mist] row fills, so the chip uses that instead, with the always-drawn Ink
 * border from [brutalSurface] keeping it legible either way.
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .brutalSurface(fill = if (row.app.isSystem) Mist else Paper, shadow = ShadowSm)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
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
                    .testTag("sensitivity-chip")
                    .brutalSurface(fill = Cream, shadow = 0.dp)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        ActivityPicker(
            current = row.activity,
            onSelect = onActivityChange,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}

@Composable
private fun ActivityPicker(
    current: BackgroundActivity,
    onSelect: (BackgroundActivity) -> Unit,
    modifier: Modifier = Modifier,
) {
    // A smaller, single-line type size for these three buttons only - see
    // the width-budget note above. labelSmall is the smallest style the
    // theme defines (12sp); BrutalButton's own Text() already forces
    // maxLines = 1, so a label that still can't fit here truncates with an
    // ellipsis rather than force-wrapping mid-word.
    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.labelSmall) {
        Row(
            modifier = modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackgroundActivity.entries.forEach { option ->
                val active = option == current
                BrutalButton(
                    text = label(option),
                    // The active option must not call back with a no-op change -
                    // there is nothing to change - but it must still read as a
                    // live, tappable control to accessibility services (see
                    // [BrutalButton]'s `isSelected`), not a disabled one.
                    onClick = { if (!active) onSelect(option) },
                    fill = if (active) Teal else Paper,
                    contentColor = if (active) Paper else InkColor,
                    isSelected = active,
                    // Inside AppRow's own slab - see the shadow note above.
                    shadow = 0.dp,
                    contentPadding = PickerButtonPadding,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private val PickerButtonPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)

/**
 * Title case, not "RESTRICTED"/"OPTIMIZED"/"UNRESTRICTED": these three words
 * deliberately match Android's own battery-usage wording (Settings > Apps >
 * [app] > Battery) so the app never teaches a second vocabulary for the same
 * setting - a Global Constraint, not a style choice. Do not abbreviate them.
 */
private fun label(activity: BackgroundActivity): String = when (activity) {
    BackgroundActivity.RESTRICTED -> "Restricted"
    BackgroundActivity.OPTIMIZED -> "Optimized"
    BackgroundActivity.UNRESTRICTED -> "Unrestricted"
}
