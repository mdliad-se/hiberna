// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * F5: brand v1.1 is explicit that Cream is the app canvas and is "never
 * white", but before this fix nothing painted it - GateScreen drew its own
 * Cream background, and every other screen (AppListScreen, AppDetailSheet,
 * PresetScreen) painted nothing, so the platform's plain white window
 * background showed through everywhere past the gate. This is a pixel-level
 * regression test, not a semantics assertion, for the same reason
 * AppRowColorTest is: a colour bug is invisible to assertIsDisplayed, which
 * only checks that something is drawn, not what colour it is.
 *
 * An empty Box with no background of its own is the strictest possible
 * check here: if JinatraTheme itself is not painting the canvas, this Box
 * has nothing else that could accidentally paint Cream behind it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JinatraThemeTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `JinatraTheme paints the Cream canvas behind content with no background of its own`() {
        compose.setContent {
            JinatraTheme {
                Box(modifier = Modifier)
            }
        }

        val decorView = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
        decorView.draw(Canvas(bitmap))

        val corner = bitmap.getPixel(2, 2)

        assertEquals(
            "expected JinatraTheme's own canvas to be filled with Cream, never left as the " +
                "platform's default window background",
            Cream.toArgb(),
            corner,
        )
        assertNotEquals(
            "the canvas must never be plain white - brand v1.1: Cream is the app canvas and " +
                "is never white",
            android.graphics.Color.WHITE,
            corner,
        )
    }
}
