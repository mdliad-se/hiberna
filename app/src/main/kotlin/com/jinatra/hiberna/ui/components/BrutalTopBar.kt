// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.ui.theme.BorderWidth
import com.jinatra.hiberna.ui.theme.InkColor
import com.jinatra.hiberna.ui.theme.Paper
import com.jinatra.hiberna.ui.theme.ShadowSm

/**
 * The brutalist top app bar v1.1 navigation gives every screen - before this,
 * the app had no bar, no title, and no back affordance anywhere (see the
 * task brief's "no proper navigation" quote). Deliberately NOT Material3's
 * `TopAppBar`: that composable paints a Material `elevation` shadow (a blur -
 * forbidden by brand v1.1, see [brutalSurface]'s own doc for why every shadow
 * in this app is a solid offset rectangle instead) on a rounded/tonal surface
 * this brand never uses. This bar is closer to plain chrome than a slab: a
 * single hard [InkColor] line along the bottom edge, drawn at [BorderWidth] -
 * the same 3px every other Ink border in this app uses - and zero corner
 * radius (there is no shape to round; it is a line, not a box). No Compose
 * `elevation` appears anywhere in this file. The title uses [MaterialTheme]'s
 * `titleLarge`, `JinatraTheme`'s `FontWeight.ExtraBold` - the weight brand
 * v1.1 calls "Archivo-weight" pending the actual Archivo font files (see
 * `JinatraTheme`'s own doc on that gap); it is capped at one line with
 * [TextOverflow.Ellipsis] rather than left to wrap into whatever [actions]
 * sits beside it.
 *
 * This bar carries no horizontal padding of its own: every screen that hosts
 * one already applies its own 16.dp (`AppListScreen`, `PresetScreen`) or
 * 24.dp (`AppDetailSheet`) content margin, and a second margin here would
 * double it. Only vertical padding is this bar's own.
 *
 * [onBack] is null on the one screen that must never show a back
 * affordance - the app list, this app's home - and non-null everywhere
 * reachable from it (`PresetScreen`, `AppDetailSheet`). It renders as a
 * [BrutalButton], not a bare icon: this app has no icon set yet (`AppRow`'s
 * own doc notes icons are not added yet), and a text button matches every
 * other control this brand already uses. [backLabel] defaults to "Back" for
 * a genuine back-stack screen (`PresetScreen`) and is overridden to "Close"
 * for a sheet that presents over another screen rather than replacing it
 * (`AppDetailSheet`) - both fire the exact same [onBack] callback, since both
 * cases return to the same place (the list); only the word differs, matching
 * how each screen actually behaves - see the task report for the fuller
 * reasoning on why this is one affordance, not two.
 *
 * [nestedInSlab] decides the back/close button's own shadow, per brand
 * v1.1: "a slab inside a slab drops its shadow entirely and keeps just the
 * border". `false` (the default) keeps the button's own [ShadowSm] - correct
 * for `PresetScreen`, whose bar sits directly on the screen's Cream/Paper
 * canvas, not inside any slab of its own, the same non-nested context
 * `AppListScreen`'s bar is in for its Presets action (which also keeps its
 * default shadow). `true` drops the shadow to zero - correct, and required,
 * for `AppDetailSheet`, whose bar sits inside that sheet's own
 * `brutalSurface(shadow = ShadowMd)` slab; a second shadow there would land
 * on the outer slab's own border rather than a neighbouring control 16.dp
 * away, the exact case this rule exists to prevent.
 *
 * [actions] is a trailing slot for whatever a specific screen's bar carries -
 * the app list's "Presets" button is the only user of it today. Judgement
 * call, reasoned in the task report: the system-apps toggle deliberately does
 * NOT live here, to avoid crowding this slot on a 360dp screen alongside a
 * title and an action that already has to fit - `AppListScreenTest` measures
 * that this bar's title and Presets action neither wrap nor overflow at
 * 360dp.
 */
@Composable
fun BrutalTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    nestedInSlab: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val strokeWidth = BorderWidth.toPx()
                drawLine(
                    color = InkColor,
                    start = Offset(0f, size.height - strokeWidth / 2f),
                    end = Offset(size.width, size.height - strokeWidth / 2f),
                    strokeWidth = strokeWidth,
                )
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            BrutalButton(
                text = backLabel,
                onClick = onBack,
                fill = Paper,
                contentColor = InkColor,
                shadow = if (nestedInSlab) 0.dp else ShadowSm,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}
