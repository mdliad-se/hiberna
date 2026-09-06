// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.ui.components.brutalSurface
import com.jinatra.hiberna.ui.theme.InkColor
import com.jinatra.hiberna.ui.theme.Paper
import com.jinatra.hiberna.ui.theme.ShadowMd
import com.jinatra.hiberna.ui.theme.ShadowSm
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
 * [state.showSystem] has no toggle here: the "Produces" interface for this
 * task exposes `onQueryChange`, `onActivityChange` and `onRowClick` only, so
 * showing/hiding system apps is plumbed through [AppListViewModel] but has no
 * affordance on this screen yet.
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
 */
@Composable
fun AppListScreen(
    state: AppListState,
    onQueryChange: (String) -> Unit,
    onActivityChange: (String, BackgroundActivity) -> Unit,
    onRowClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        BasicTextField(
            value = state.query,
            onValueChange = onQueryChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = InkColor),
            cursorBrush = SolidColor(InkColor),
            modifier = Modifier
                .fillMaxWidth()
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

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.rows.isEmpty() && state.error == null && !state.loading) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .brutalSurface(fill = Paper, shadow = 0.dp)
                            .padding(24.dp),
                    ) {
                        Text("No apps match that search.", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            items(state.rows, key = { it.app.packageName }) { row ->
                AppRow(
                    row = row,
                    onClick = { onRowClick(row.app.packageName) },
                    onActivityChange = { activity -> onActivityChange(row.app.packageName, activity) },
                )
            }
        }
    }
}
