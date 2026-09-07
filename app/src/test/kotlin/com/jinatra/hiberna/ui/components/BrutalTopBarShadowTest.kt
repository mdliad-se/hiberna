// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.ui.theme.InkColor
import com.jinatra.hiberna.ui.theme.ShadowSm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * F2 review finding: [BrutalTopBar]'s back/close button used to hardcode
 * `shadow = 0.dp` unconditionally. That is only correct when the bar sits
 * inside another slab's own border (`AppDetailSheet`, brand v1.1: "a slab
 * inside a slab drops its shadow"); it was wrong for `PresetScreen`, whose
 * bar is not nested in any slab and whose back button must keep the same
 * default [ShadowSm] `AppListScreen`'s Presets action keeps in that identical
 * non-nested context. Pins both cases at the pixel level, the same technique
 * `BrutalSurfaceTest` uses for the same underlying reason (a [Dp]-only
 * assertion cannot see whether a shadow rect is actually drawn, since the
 * unpressed offset is always zero regardless of the shadow depth).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrutalTopBarShadowTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val containerColor = Color(0xFF00C853) // green: "nothing drawn here"

    private fun renderAndProbeBackButtonShadow(nestedInSlab: Boolean): Int {
        compose.setContent {
            Box(Modifier.size(240.dp).background(containerColor)) {
                BrutalTopBar(title = "Screen", onBack = {}, nestedInSlab = nestedInSlab)
            }
        }

        val density = compose.density
        // The "Back" text sits inside a clickable BrutalButton, which merges
        // its subtree into one semantics node - the same reasoning
        // AppDetailSheetTest's own 360dp measurement test relies on for a
        // button's merged bounds equalling its full clickable area (i.e. the
        // exact rectangle brutalSurface draws its border/shadow around).
        val bounds = compose.onNodeWithText("Back").fetchSemanticsNode().boundsInRoot

        val decorView = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
        decorView.draw(Canvas(bitmap))

        // A point just past the button's own bottom-right corner, inside the
        // shadow-only strip when a shadow is drawn there (same geometry
        // BrutalSurfaceTest's shadowProbeDp relies on: bounds.right/bottom +
        // half the shadow depth lands inside the shadow rect but outside the
        // button's own fill).
        val shadowPx = with(density) { ShadowSm.toPx() }
        val px = (bounds.right + shadowPx / 2).roundToInt()
        val py = (bounds.bottom + shadowPx / 2).roundToInt()
        return bitmap.getPixel(px, py)
    }

    @Test
    fun `a bar not nested in another slab keeps its back button's shadow`() {
        // PresetScreen's context: the bar sits directly on the screen's own
        // canvas, not inside any brutalSurface slab.
        val pixel = renderAndProbeBackButtonShadow(nestedInSlab = false)

        assertEquals(
            "expected Ink in the shadow-only strip - a non-nested bar's back " +
                "button must keep its default ShadowSm",
            InkColor.toArgb(),
            pixel,
        )
    }

    @Test
    fun `a bar nested in another slab drops its back button's shadow`() {
        // AppDetailSheet's context: the bar sits inside that sheet's own
        // brutalSurface(shadow = ShadowMd) slab.
        val pixel = renderAndProbeBackButtonShadow(nestedInSlab = true)

        assertNotEquals(
            "a slab inside a slab must drop its shadow entirely - no Ink " +
                "should be drawn past the button's own border",
            InkColor.toArgb(),
            pixel,
        )
    }
}
