// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.ui.theme.BorderWidth
import com.jinatra.hiberna.ui.theme.InkColor

/**
 * The two Ink-offset values that make up the neubrutalist shadow at a given
 * moment, kept as a pure function of [shadow] and [pressed] so the geometry
 * is unit-testable without any Compose drawing machinery - see
 * BrutalGeometryTest.
 *
 * - Unpressed: the element sits flat, [offset] is zero, [shadowDepth] is the
 *   full [shadow] value.
 * - Pressed: the element translates by exactly [shadow] (reading as pushed
 *   into the page) and the shadow collapses to zero depth.
 */
internal data class BrutalGeometry(val offset: Dp, val shadowDepth: Dp)

internal fun brutalGeometry(shadow: Dp, pressed: Boolean): BrutalGeometry =
    if (pressed) {
        BrutalGeometry(offset = shadow, shadowDepth = 0.dp)
    } else {
        BrutalGeometry(offset = 0.dp, shadowDepth = shadow)
    }

/**
 * A neubrutalist shadow is a second shape, not lighting - a solid offset
 * duplicate of the element's silhouette, zero blur, zero radius. Compose's
 * `elevation` blurs, so it is never used here; the shadow is drawn as a
 * solid offset rectangle behind the surface.
 *
 * Pass `shadow = 0.dp` for a disabled control: it loses its shadow entirely,
 * because a control that cannot be pushed should not look pushable.
 */
fun Modifier.brutalSurface(
    fill: Color,
    shadow: Dp,
    pressed: Boolean = false,
): Modifier {
    val geometry = brutalGeometry(shadow, pressed)
    return this
        .offset(x = geometry.offset, y = geometry.offset)
        .drawBehind {
            if (geometry.shadowDepth > 0.dp) {
                val d = geometry.shadowDepth.toPx()
                drawRect(
                    color = InkColor,
                    topLeft = Offset(d, d),
                    size = Size(size.width, size.height),
                )
            }
        }
        .background(fill, RectangleShape)
        .border(BorderWidth, InkColor, RectangleShape)
}
