// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.severity.Severity
import com.jinatra.hiberna.ui.components.ActivityPicker
import com.jinatra.hiberna.ui.components.brutalSurface
import com.jinatra.hiberna.ui.theme.Cream
import com.jinatra.hiberna.ui.theme.InkColor
import com.jinatra.hiberna.ui.theme.Mist
import com.jinatra.hiberna.ui.theme.Paper
import com.jinatra.hiberna.ui.theme.Product
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
 * column, not beside it: three full-size buttons in a `Row` next to a label
 * `Column` on a real 360-411dp phone would squeeze that label to nothing,
 * and Robolectric's semantic tests cannot see that squeeze (they don't
 * measure pixels). Stacking instead, with every button carrying
 * `Modifier.weight(1f)`, gives the label its full width above and the three
 * buttons an even three-way split below.
 *
 * [ActivityPicker] itself (Task 13 extracted it out of this file so
 * [com.jinatra.hiberna.ui.screens.detail.AppDetailSheet] can reuse the exact
 * same picker rather than growing a second one) owns the 360dp width-budget
 * tuning - see its own doc - so this row only has to place it, not retune
 * it.
 *
 * The tier badge deliberately does NOT use [com.jinatra.hiberna.ui.theme.Signal]
 * for any of the four tiers: that colour is reserved for one highlight per
 * screen (see Tokens.kt), and severity is a per-row, potentially-many-matches
 * property - a list screen can easily show several [Severity.WILL_BREAK] rows
 * at once, so spending Signal on even the loudest tier would multiply that
 * highlight the moment two such rows are visible together. [Severity.WILL_BREAK]'s
 * badge is v1's sensitivity chip, unchanged: [Cream] fill, Ink text, a full
 * explanatory sentence rather than a single word - the loudest treatment
 * available without Signal. It also must not reuse [Mist]: a system row's own
 * background is filled with [Mist], so an app that is both sensitive *and* a
 * system app (visible as soon as `showSystem` is on) would render a chip that
 * is colour-identical to the row behind it, leaving only a 3px border as the
 * sole warning before a restriction that could break the app - [Cream] reads
 * clearly against both [Paper] and [Mist] row fills, so it is what earns the
 * top tier's badge that colour.
 *
 * The other three tiers, introduced in Task 1 of v1.1, are deliberately three
 * more treatments, not near-duplicates of each other or of WILL_BREAK's chip -
 * see `docs/spine/specs/2026-09-07-hiberna-v1.1-design.md` section 1 and the
 * task report for the fuller reasoning:
 * - [Severity.RECOMMENDED] is a solid [Teal] badge, [Paper] text - hiberna's
 *   brand-affirmative colour, used here purely as a status label rather than
 *   a primary action (Teal never stops being reserved for that; see brand
 *   v1.1's "[Product] never replaces Teal for primary actions", which cuts
 *   both ways).
 * - [Severity.SAFE] renders nothing at all. "Nothing detected" earns no
 *   badge, and on a list of hundreds of apps most of which will be this tier,
 *   silence is the only treatment that does not become noise - it is also
 *   what makes [Severity.RECOMMENDED]'s badge stand out as an actual signal
 *   rather than one badge among a wall of them.
 * - [Severity.CAUTION] is an outline-only badge - [brutalSurface] with a
 *   transparent fill, so only its Ink border and its label draw; its fill is
 *   deliberately whatever is already behind it (this is the one tier most
 *   often paired with a system row's [Mist] fill, so it cannot reuse a solid
 *   colour without risking exactly the collision [Cream] was chosen to avoid
 *   for WILL_BREAK above). Next to WILL_BREAK's solid [Cream] chip and full
 *   sentence, an outline-only badge reads as clearly quieter - the two are
 *   never confusable as "the same warning twice", which is the judgment call
 *   the task brief asked to have reasoned through: both tiers mean "be
 *   careful", but only one of them is solid, filled, and spelled out. This
 *   tier's label is not always the same word, though: a system app reads
 *   "Caution", but a row whose [Sensitivity] is [Sensitivity.UNKNOWN] reads
 *   "Couldn't check this app" instead - [Severity.severityOf] badges both the
 *   same tier, but only one of them is a judgment call, and the other is an
 *   admission that detection threw. Reusing WILL_BREAK's "May stop working if
 *   restricted" sentence for the latter would claim a check happened when it
 *   did not - see that function's own doc.
 *
 * Icons are loaded per-row by the UI layer rather than held on [InstalledApp]
 * - see that class's doc - but are not added here yet: no test in this task
 * calls for one, and a `Drawable` fetched per row still needs a safe
 * degrade-to-box path before it belongs in a real device screen.
 *
 * [selected] and [onLongClick] are Task 12's multi-select affordance: a
 * long-press enters selection mode and selects this row, and (while any row
 * is selected) a normal tap toggles this row's selection instead of calling
 * [onClick] - the caller (`AppListScreen`) is the one that decides which
 * behaviour a tap gets, since only it knows whether the selection is
 * currently non-empty; this composable only ever renders whatever `onClick`
 * it is handed. A selected row keeps its 3px border and its own shadow (it is
 * not a slab inside another slab, so brand v1.1's "drop the shadow" rule does
 * not apply here) but swaps its fill to [Product] - hiberna's own accent
 * colour, reserved for the app's accent surfaces rather than a primary action
 * button - and marks `semantics { selected = true }` so a screen reader
 * announces the state change too, not just the colour. [Signal] is never
 * used for this: it is reserved for one highlight per screen, already spent
 * on the error banner, and multi-select can highlight many rows at once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(
    row: AppRowState,
    onClick: () -> Unit,
    onActivityChange: (BackgroundActivity) -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    // Captured under a differently-named local before entering the
    // `semantics {}` lambda below: inside that lambda, an unqualified
    // `selected` could otherwise resolve to the block's own implicit
    // `SemanticsPropertyReceiver.selected` extension property rather than
    // this composable's own `selected` parameter, given they share a name.
    val isSelected = selected
    val fill = when {
        isSelected -> Product
        row.app.isSystem -> Mist
        else -> Paper
    }
    val contentColor = if (isSelected) Paper else InkColor
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (isSelected) this.selected = true }
            .brutalSurface(fill = fill, shadow = ShadowSm)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(16.dp),
    ) {
        Text(text = row.app.label, style = MaterialTheme.typography.titleLarge, color = contentColor)
        Text(
            text = row.app.packageName,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
        // The severity tier's badge - see this file's own doc above for why
        // Task 1's other three tiers get the treatments they do, and why the
        // WILL_BREAK case below is untouched from v1's sensitivity chip.
        when (row.severity) {
            Severity.WILL_BREAK -> Text(
                text = "May stop working if restricted",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .testTag("sensitivity-chip")
                    .brutalSurface(fill = Cream, shadow = 0.dp)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )

            Severity.CAUTION -> Text(
                text = if (row.sensitivity == Sensitivity.UNKNOWN) {
                    "Couldn't check this app"
                } else {
                    "Caution"
                },
                style = MaterialTheme.typography.labelSmall,
                color = InkColor,
                modifier = Modifier
                    .testTag("tier-badge-caution")
                    .brutalSurface(fill = Color.Transparent, shadow = 0.dp)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )

            Severity.RECOMMENDED -> Text(
                text = "Recommended",
                style = MaterialTheme.typography.labelSmall,
                color = Paper,
                modifier = Modifier
                    .testTag("tier-badge-recommended")
                    .brutalSurface(fill = Teal, shadow = 0.dp)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )

            Severity.SAFE -> Unit // nothing detected, nothing shown - see doc above.
        }
        ActivityPicker(
            current = row.activity,
            onSelect = onActivityChange,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}
