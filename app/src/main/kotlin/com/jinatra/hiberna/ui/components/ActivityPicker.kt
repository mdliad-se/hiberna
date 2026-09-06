// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.ui.theme.InkColor
import com.jinatra.hiberna.ui.theme.Paper
import com.jinatra.hiberna.ui.theme.Teal

/**
 * The one tri-state background-activity picker in the app - extracted out of
 * `AppRow` (Task 11) so `AppDetailSheet` (Task 13) can reuse it rather than
 * grow a second copy of the same setting. Two pickers that can drift apart
 * on the same setting is exactly the kind of duplication a reviewer already
 * caught on the guardrail predicate this task's neighbour introduced - see
 * `AppListViewModel.skippedCount`'s doc for that precedent.
 *
 * A smaller, single-line type size for these three buttons only, tuned for
 * the narrowest phone width this app targets: `labelSmall` is the smallest
 * style the theme defines (12sp), `BrutalButton`'s own `Text()` already
 * forces `maxLines = 1`, and [PickerButtonPadding] is deliberately tighter
 * than [BrutalButton]'s default so "Unrestricted" still lays out on one line
 * at 360dp inside a three-way `Row` split - see `AppListScreenTest`'s
 * `w360dp-h640dp` layout-measurement test, which asserts the actual laid out
 * text height rather than trusting arithmetic. Both call sites (`AppRow`,
 * `AppDetailSheet`) render at this same minimum width, so both get this
 * fit for free rather than needing their own tuning.
 *
 * Always rendered with `shadow = 0.dp`: both call sites place this inside
 * their own slab (`AppRow`'s row surface, `AppDetailSheet`'s card), and brand
 * v1.1 is explicit that "a slab inside a slab drops its shadow entirely and
 * keeps just the border" - this is never used any other way, so the zero
 * shadow lives here rather than being repeated at every call site.
 */
@Composable
fun ActivityPicker(
    current: BackgroundActivity,
    onSelect: (BackgroundActivity) -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.labelSmall) {
        Row(
            modifier = modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackgroundActivity.entries.forEach { option ->
                val active = option == current
                BrutalButton(
                    text = activityLabel(option),
                    // The active option must not call back with a no-op change -
                    // there is nothing to change - but it must still read as a
                    // live, tappable control to accessibility services (see
                    // BrutalButton's `isSelected`), not a disabled one.
                    onClick = { if (!active) onSelect(option) },
                    fill = if (active) Teal else Paper,
                    contentColor = if (active) Paper else InkColor,
                    isSelected = active,
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
fun activityLabel(activity: BackgroundActivity): String = when (activity) {
    BackgroundActivity.RESTRICTED -> "Restricted"
    BackgroundActivity.OPTIMIZED -> "Optimized"
    BackgroundActivity.UNRESTRICTED -> "Unrestricted"
}
