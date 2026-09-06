// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.ui.theme.Cream
import com.jinatra.hiberna.ui.theme.JinatraTheme
import com.jinatra.hiberna.ui.theme.Mist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * Pixel-level regression test for F1: an app that is both sensitive AND a
 * system app must not render its sensitivity chip in the same colour as its
 * own row background. No fake in [AppListViewModelTest] can see this - every
 * sensitive fixture there (`com.example.sms` via `FakeSensitivityDetector`)
 * is a non-system app, and the only system fixture (`com.android.systemui`)
 * is never sensitive - and the bug is a colour collision, not a state or
 * mapping bug, so a semantic-tree assertion (`assertIsDisplayed`, as used in
 * [AppListScreenTest]) would pass either way: the chip is displayed and
 * legible to `assertIsDisplayed` regardless of what colour it is filled
 * with.
 *
 * Follows [com.jinatra.hiberna.ui.components.BrutalSurfaceTest]'s capture
 * technique: `captureToImage()` hangs under Robolectric in this
 * configuration (see that file's doc), so this draws the decor view
 * directly into a software [Bitmap] instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppRowColorTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val systemAndSensitiveRow = AppRowState(
        app = InstalledApp(
            packageName = "com.android.systemservice",
            label = "System Service",
            uid = 10099,
            isSystem = true,
            isEnabled = true,
        ),
        activity = BackgroundActivity.OPTIMIZED,
        dataBlocked = false,
        sensitivity = Sensitivity.LIKELY_BREAKS,
    )

    @Test
    fun `the sensitivity chip on a sensitive system row is not the same colour as the row itself`() {
        compose.setContent {
            JinatraTheme {
                AppRow(
                    row = systemAndSensitiveRow,
                    onClick = {},
                    onActivityChange = {},
                    modifier = Modifier.testTag("row"),
                )
            }
        }

        val density = compose.density
        val decorView = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
        decorView.draw(Canvas(bitmap))

        // A point safely inside the row's own fill: past the 3px Ink border,
        // well before the row's 16dp content padding where the label/package
        // text starts, and outside the chip entirely.
        val rowBounds = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        val rowFillPx = with(density) {
            bitmap.getPixel(
                (rowBounds.left + 6.dp.toPx()).roundToInt(),
                (rowBounds.top + 6.dp.toPx()).roundToInt(),
            )
        }

        // A point safely inside the chip's own fill: past its 3px Ink
        // border and before its inset text (6.dp horizontal padding), so
        // never a text glyph pixel. `useUnmergedTree = true` because the
        // row's own `clickable` merges all descendant semantics (including
        // this tag) into the row's single merged node for accessibility.
        val chipBounds = compose.onNodeWithTag("sensitivity-chip", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val chipFillPx = with(density) {
            bitmap.getPixel(
                (chipBounds.left + 4.5.dp.toPx()).roundToInt(),
                ((chipBounds.top + chipBounds.bottom) / 2f).roundToInt(),
            )
        }

        assertEquals(
            "expected the system row itself to still be filled with Mist",
            Mist.toArgb(),
            rowFillPx,
        )
        assertEquals(
            "expected the sensitivity chip to be filled with Cream, not the row's own colour",
            Cream.toArgb(),
            chipFillPx,
        )
        assertNotEquals(
            "the chip must never be colour-identical to the row it sits on - that leaves only " +
                "a 3px border as the sole warning before a restriction that could break the app",
            rowFillPx,
            chipFillPx,
        )
    }
}
