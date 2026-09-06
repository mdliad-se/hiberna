// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.ui.theme.InkColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * Pixel-level tests for [Modifier.brutalSurface] itself, not just the pure
 * [brutalGeometry] helper it is built from. BrutalGeometryTest pins the
 * numbers; this file pins that those numbers actually reach the canvas - a
 * stale/inlined `brutalSurface` that ignored [brutalGeometry] entirely would
 * still pass BrutalGeometryTest but must fail here.
 *
 * `captureToImage()` (the documented approach) hangs under Robolectric
 * 4.16.1 in this configuration: its `forceRedraw()` registers a
 * `ViewTreeObserver.OnDrawListener`, calls `View.invalidate()`, then busy-waits
 * on a plain `Thread.sleep` loop for that listener to fire. That wait never
 * pumps Robolectric's paused main `Looper`, so the queued draw traversal
 * never runs and every call times out with `ComposeTimeoutException`
 * ("Condition still not satisfied after 2000 ms"), regardless of
 * `@GraphicsMode(NATIVE)` or `@LooperMode(LEGACY)` (both tried, neither
 * changed the outcome - see .spine/task-9-report.md for the full trace).
 *
 * Instead, this test draws the decor view directly into a software
 * `Bitmap` via `View.draw(Canvas)`, which is a synchronous call needing no
 * Looper pumping and is real pixel output under Robolectric's native
 * (Skia) graphics mode - it exercises the same `drawBehind`/`background`/
 * `border` draw calls `Modifier.brutalSurface` issues, just captured by a
 * different mechanism than `captureToImage()`.
 *
 * Container background, element fill, and the Ink shadow are three mutually
 * unambiguous colours so every sampled pixel identifies exactly one of them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrutalSurfaceTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val containerColor = Color(0xFF00C853) // green: "nothing drawn here"
    private val fillColor = Color(0xFFFF00FF) // magenta: the element's own fill
    private val containerSizeDp = 64.dp
    private val elementSizeDp = 20.dp
    private val shadowDp = 10.dp

    /**
     * With containerSize=64, elementSize=20, shadow=10: unpressed, the shadow
     * rect spans local (10,10)-(30,30) while the element occupies (0,0)-(20,20).
     * (25,25) sits in the shadow-only strip - inside the shadow rect, outside
     * the element - so it isolates the shadow from the fill/border.
     * Pressed, the element itself translates to (10,10)-(30,30) and (25,25)
     * lands well inside its fill (the border is only the outer 3dp shell), so
     * the same point flips from Ink to fill with no shadow anywhere.
     */
    private val shadowProbeDp = 25.dp

    // Center of the unpressed element's own fill, away from its border.
    private val elementCenterDp = 10.dp

    /**
     * Renders the surface under test, draws the whole decor view into a
     * software bitmap, and returns a sampler for points expressed as local
     * dp offsets from the container's own top-left corner.
     */
    private fun render(shadow: Dp, pressed: Boolean): (Dp, Dp) -> Int {
        compose.setContent {
            Box(
                Modifier
                    .size(containerSizeDp)
                    .background(containerColor)
                    .testTag("container"),
                contentAlignment = Alignment.TopStart,
            ) {
                Box(
                    Modifier
                        .size(elementSizeDp)
                        .brutalSurface(fill = fillColor, shadow = shadow, pressed = pressed),
                )
            }
        }

        val density = compose.density
        val bounds = compose.onNodeWithTag("container").fetchSemanticsNode().boundsInRoot

        val decorView = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
        decorView.draw(Canvas(bitmap))

        return { x, y ->
            val px = (bounds.left + with(density) { x.toPx() }).roundToInt()
            val py = (bounds.top + with(density) { y.toPx() }).roundToInt()
            bitmap.getPixel(px, py)
        }
    }

    @Test
    fun `unpressed surface draws an Ink shadow offset from the element fill`() {
        val pixelAt = render(shadow = shadowDp, pressed = false)

        assertEquals(
            "expected the element's own fill at its center",
            fillColor.toArgb(),
            pixelAt(elementCenterDp, elementCenterDp),
        )
        assertEquals(
            "expected Ink in the shadow-only strip down-right of the element",
            InkColor.toArgb(),
            pixelAt(shadowProbeDp, shadowProbeDp),
        )
    }

    @Test
    fun `pressed surface collapses the shadow and translates by the shadow offset`() {
        val pixelAt = render(shadow = shadowDp, pressed = true)

        val probe = pixelAt(shadowProbeDp, shadowProbeDp)
        assertNotEquals(
            "shadow must have collapsed to zero on press, so no Ink should remain here",
            InkColor.toArgb(),
            probe,
        )
        assertEquals(
            "the element must have translated by exactly the shadow offset, so its fill " +
                "now covers the point the shadow used to occupy",
            fillColor.toArgb(),
            probe,
        )
    }

    // `setContent` may only run once per test, so the two press states of a
    // disabled (shadow = 0) surface get one test each rather than sharing one.

    @Test
    fun `disabled surface draws no shadow when unpressed`() {
        val pixelAt = render(shadow = 0.dp, pressed = false)

        assertNotEquals(InkColor.toArgb(), pixelAt(shadowProbeDp, shadowProbeDp))
        assertEquals(
            "the element itself must still render at its own position",
            fillColor.toArgb(),
            pixelAt(elementCenterDp, elementCenterDp),
        )
    }

    @Test
    fun `disabled surface draws no shadow when pressed`() {
        val pixelAt = render(shadow = 0.dp, pressed = true)

        assertNotEquals(InkColor.toArgb(), pixelAt(shadowProbeDp, shadowProbeDp))
        assertEquals(
            "the element itself must still render at its own position",
            fillColor.toArgb(),
            pixelAt(elementCenterDp, elementCenterDp),
        )
    }
}
