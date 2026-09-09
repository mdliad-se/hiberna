// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.preset.Preset
import com.jinatra.hiberna.ui.components.BrutalButton
import com.jinatra.hiberna.ui.components.BrutalTopBar
import com.jinatra.hiberna.ui.components.brutalSurface
import com.jinatra.hiberna.ui.theme.InkColor
import com.jinatra.hiberna.ui.theme.Paper
import com.jinatra.hiberna.ui.theme.ShadowMd
import com.jinatra.hiberna.ui.theme.ShadowSm
import com.jinatra.hiberna.ui.theme.Mist
import com.jinatra.hiberna.ui.theme.Signal

/**
 * The screen the app exists for: every installed app, its real background
 * state, editable in place through [AppRow]'s activity picker.
 *
 * Layout is three stacked pieces, not the brief's `TextField` sized
 * `fillMaxSize().weight(0f)` next to a sibling `LazyColumn` (incoherent - a
 * fillMaxSize element with zero weight in a Column either claims no space or
 * fights its own size request depending on the other siblings' weights, and
 * either way was never going to produce "search pinned, list scrolling"):
 * the search field and (when present) the error banner are normal,
 * non-scrolling children sized to their own content, and only the
 * [LazyColumn] takes `Modifier.weight(1f)` to fill and scroll the rest of
 * the column. That keeps the search box and any error always on screen while
 * a long app list scrolls independently under them.
 *
 * [state.showSystem] / [onShowSystemChange]: Task 11's "Produces" interface
 * for this screen exposed `onQueryChange`, `onActivityChange` and
 * `onRowClick` only, so showing/hiding system apps was plumbed through
 * [AppListViewModel] with no affordance on this screen at all - a gap Task 2
 * of v1.1 closes with the inline Switch+Text row below the search field (see
 * that row's own comment for why it is not one of [BrutalTopBar]'s actions).
 * (Task 12 adds the selection and bulk-apply parameters below; see that note
 * for their shape.)
 *
 * The search field is a [BasicTextField] wrapped in [brutalSurface], not a
 * bare Material3 `TextField`: this is the most prominent element on the
 * screen the app exists for, and every other surface here and in
 * `GateScreen` is brutalist - Ink border, zero radius, no Material
 * `elevation`. The placeholder is drawn inside [BasicTextField]'s own
 * `decorationBox` (not a separate sibling) so it merges into the same
 * semantics node as the field itself, matching `onNodeWithText("Search
 * apps").performTextInput(...)`'s expectation of one node that is both
 * labelled and editable.
 *
 * Multi-select and bulk apply (Task 12) live here too, tuned to not collide
 * with Task 13's detail sheet: [selected] being non-empty *is* "selection
 * mode" - there is no separate boolean to fall out of sync with it. A
 * long-press on [AppRow] always calls [onToggleSelection] (which both enters
 * selection mode and selects that row, since it starts from an empty set);
 * a normal tap calls [onToggleSelection] too, but only while [selected] is
 * already non-empty - otherwise it calls [onRowClick], which Task 13 wires to
 * the detail sheet. [BulkBar] itself only renders while [selected] is
 * non-empty, and its own cancel action is [onCancelSelection], which clears
 * the selection and - since that emptiness is what defines selection mode -
 * exits it in the same step.
 *
 * [onOpenPresets] (Task 14) is the one entry point into `PresetScreen` -
 * without it, a fully built and tested screen had no way for a user to ever
 * reach it, exactly the "unreachable" gap the task report calls out. It sits
 * in a header row above the search field, its own 16.dp top margin away from
 * that field's shadowed [brutalSurface] (brand v1.1: two shadowed elements
 * never closer than 16.dp).
 */
@Composable
fun AppListScreen(
    state: AppListState,
    onQueryChange: (String) -> Unit,
    onActivityChange: (String, BackgroundActivity) -> Unit,
    onRowClick: (String) -> Unit,
    onShowSystemChange: (Boolean) -> Unit = {},
    onStateFilterChange: (StateFilter) -> Unit = {},
    onSortByChange: (SortBy) -> Unit = {},
    onGrantUsageAccess: () -> Unit = {},
    selected: Set<String> = emptySet(),
    presets: List<Preset> = emptyList(),
    skippedCountFor: (Preset) -> Int = { 0 },
    onToggleSelection: (String) -> Unit = {},
    onApplyPreset: (Preset) -> Unit = {},
    onCancelSelection: () -> Unit = {},
    onOpenPresets: () -> Unit = {},
    onDismissBulkSummary: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        BrutalTopBar(
            title = "hiberna",
            actions = {
                BrutalButton(
                    text = "Presets",
                    onClick = onOpenPresets,
                    fill = Paper,
                    contentColor = InkColor,
                )
            },
        )

        BasicTextField(
            value = state.query,
            onValueChange = onQueryChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = InkColor),
            cursorBrush = SolidColor(InkColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .brutalSurface(fill = Paper, shadow = ShadowSm),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (state.query.isEmpty()) {
                        Text(
                            text = "Search apps",
                            style = MaterialTheme.typography.bodyLarge,
                            color = InkColor.copy(alpha = 0.6f),
                        )
                    }
                    innerTextField()
                }
            },
        )

        state.error?.let { message ->
            Text(
                text = message,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .brutalSurface(fill = Signal, shadow = ShadowMd)
                    .padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        // F1: the bulk-apply summary was computed, tested and dropped on the
        // floor - never rendered anywhere, so a user who bulk-applied a
        // preset across a hundred apps saw the selection clear with no word
        // on what actually happened. Rendered here in Mist, not Signal: this
        // screen's error banner directly above already spends this screen's
        // one Signal highlight (brand v1.1: Signal at most once per screen),
        // and a bulk-apply summary is informational, not an error - Mist is
        // the brand's own "secondary surface" token, distinct from both the
        // Signal error banner and the Cream/Paper canvas.
        state.bulkSummary?.let { summary ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .brutalSurface(fill = Mist, shadow = ShadowSm)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(end = 16.dp),
                )
                BrutalButton(
                    text = "Dismiss",
                    onClick = onDismissBulkSummary,
                    fill = Paper,
                    contentColor = InkColor,
                    shadow = 0.dp,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // F3/Task 11 gap: AppListViewModel.showSystem/onShowSystemChange
            // were fully plumbed through the view model with no affordance
            // anywhere on this screen to reach them. Placed as the list's own
            // first item - scrolling with it - rather than pinned above
            // alongside the search field: a pinned toggle would permanently
            // shrink the LazyColumn's own viewport (verified the hard way -
            // it pushed a real row below the fold on a short screen), and it
            // is not in BrutalTopBar's actions slot either, since that bar
            // already carries a title plus the Presets action and
            // AppListScreenTest measures that combination filling a 360dp
            // screen on its own - a third control there risks exactly the
            // crowding the task brief called out. This Switch+Text
            // `toggleable` row follows the same convention
            // AppDetailSheet/PresetScreen already use for their own switches.
            // Filter and sort share ONE list item, and the metrics window
            // moved to the detail sheet. Two separate control rows plus a
            // window caption pushed real rows below the fold on a 360dp
            // screen - the exact cost the show-system toggle's comment below
            // already warns about, caught by AppListScreenTest rather than by
            // guesswork. Chrome on this screen is charged against the list it
            // exists to show, so it gets one row.
            item(key = "list-controls") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StateFilter.entries.forEach { filter ->
                        val isSelected = state.stateFilter == filter
                        BrutalButton(
                            // The count is part of the label, not a separate
                            // badge: it answers "how many have I already
                            // restricted" without a tap, which is the whole
                            // reason the filter exists.
                            text = "${filter.label} ${state.stateCounts[filter] ?: 0}",
                            onClick = { onStateFilterChange(filter) },
                            fill = if (isSelected) InkColor else Paper,
                            contentColor = if (isSelected) Paper else InkColor,
                            isSelected = isSelected,
                            shadow = if (isSelected) 0.dp else ShadowSm,
                        )
                    }

                    Text(
                        text = "Sort",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    // Severity stays first and selected by default: it is what
                    // answers "where do I start", so the two metric sorts are
                    // additions to it rather than replacements.
                    SortBy.entries.forEach { option ->
                        val isSelected = state.sortBy == option
                        BrutalButton(
                            text = option.label,
                            onClick = { onSortByChange(option) },
                            fill = if (isSelected) InkColor else Paper,
                            contentColor = if (isSelected) Paper else InkColor,
                            isSelected = isSelected,
                            shadow = if (isSelected) 0.dp else ShadowSm,
                        )
                    }
                }
            }

            // The one-time usage-access prompt. Usage access reveals when
            // every app on the device was opened, which is more personal than
            // the three settings this app otherwise changes - so it is asked
            // for plainly and never granted silently, and declining leaves
            // everything else working.
            if (state.needsUsageAccess) {
                item(key = "usage-access-prompt") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .brutalSurface(fill = Mist, shadow = ShadowSm)
                            .padding(16.dp)
                            .testTag("usage-access-prompt"),
                    ) {
                        Text(
                            text = "Show runtime per app?",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "hiberna needs usage access to read how long each app " +
                                "has run. That also reveals when every app on this device " +
                                "was opened. Nothing leaves the phone, and you can turn it " +
                                "off again here at any time.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        BrutalButton(
                            text = "Allow usage access",
                            onClick = onGrantUsageAccess,
                            fill = Paper,
                            contentColor = InkColor,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }

            item(key = "show-system-toggle") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.showSystem,
                            role = Role.Switch,
                            onValueChange = onShowSystemChange,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // onCheckedChange = null: the enclosing Row's toggleable
                    // above is the single tap target (label included),
                    // matching every other Switch+Text row this app already
                    // uses.
                    Switch(checked = state.showSystem, onCheckedChange = null)
                    Text(
                        text = "Show system apps",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            if (state.rows.isEmpty() && state.error == null && !state.loading) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .brutalSurface(fill = Paper, shadow = 0.dp)
                            .padding(24.dp),
                    ) {
                        // An empty list has three different causes now, and
                        // blaming the search for all of them tells a user to
                        // clear a search box they never typed in. "Nothing is
                        // restricted" is also a legitimate answer, not a
                        // failure - a failed read sets state.error and is
                        // rendered above instead.
                        Text(
                            text = when {
                                state.query.isNotBlank() && state.stateFilter != StateFilter.ALL ->
                                    "No ${state.stateFilter.label.lowercase()} apps match that search."
                                state.query.isNotBlank() -> "No apps match that search."
                                state.stateFilter != StateFilter.ALL ->
                                    "Nothing is ${state.stateFilter.label.lowercase()} right now."
                                else -> "No apps to show."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            items(state.rows, key = { it.app.packageName }) { row ->
                val pkg = row.app.packageName
                val selectionMode = selected.isNotEmpty()
                AppRow(
                    row = row,
                    onClick = {
                        if (selectionMode) onToggleSelection(pkg) else onRowClick(pkg)
                    },
                    onActivityChange = { activity -> onActivityChange(pkg, activity) },
                    selected = pkg in selected,
                    onLongClick = { onToggleSelection(pkg) },
                )
            }
        }

        if (selected.isNotEmpty()) {
            BulkBar(
                selectedCount = selected.size,
                presets = presets,
                skippedCountFor = skippedCountFor,
                onApply = onApplyPreset,
                onClear = onCancelSelection,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
    }
}
