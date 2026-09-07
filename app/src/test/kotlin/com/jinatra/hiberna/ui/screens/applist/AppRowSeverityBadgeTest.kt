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
import com.jinatra.hiberna.ui.theme.JinatraTheme
import com.jinatra.hiberna.ui.theme.Mist
import com.jinatra.hiberna.ui.theme.Teal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * Pixel-level coverage for the four tier badges - see the task report for
 * why each tier gets the treatment it does, and why none of them spends the
 * screen's one [com.jinatra.hiberna.ui.theme.Signal] highlight (already
 * covered, for the WILL_BREAK/sensitive case, by [AppRowColorTest] and
 * `AppListScreenTest`'s "flags a sensitive app without spending the screen's
 * one Signal highlight" - this file deliberately leaves that tier's existing
 * chip, text and testTag untouched and only adds coverage for the three
 * tiers Task 1 introduces).
 *
 * Follows [AppRowColorTest]'s capture technique (`captureToImage()` hangs
 * under Robolectric in this configuration) and its `@GraphicsMode(NATIVE)` -
 * legacy graphics mode does not measure real font metrics/layout and would
 * pass this test falsely.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp")
class AppRowSeverityBadgeTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val recommendedRow = AppRowState(
        app = InstalledApp("com.example.idle", "Idle App", 10201, isSystem = false, isEnabled = true),
        activity = BackgroundActivity.UNRESTRICTED,
        dataBlocked = false,
        sensitivity = Sensitivity.NONE,
    )

    private val cautionRow = AppRowState(
        app = InstalledApp("com.android.systemservice", "System Service", 10202, isSystem = true, isEnabled = true),
        activity = BackgroundActivity.OPTIMIZED,
        dataBlocked = false,
        sensitivity = Sensitivity.NONE,
    )

    private val safeRow = AppRowState(
        app = InstalledApp("com.example.ordinary", "Ordinary App", 10203, isSystem = false, isEnabled = true),
        activity = BackgroundActivity.RESTRICTED,
        dataBlocked = false,
        sensitivity = Sensitivity.NONE,
    )

    private fun bitmapOf(activity: ComponentActivity): Bitmap {
        val decorView = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
        decorView.draw(Canvas(bitmap))
        return bitmap
    }

    @Test
    fun `a Recommended row shows a solid Teal badge`() {
        compose.setContent {
            JinatraTheme {
                AppRow(row = recommendedRow, onClick = {}, onActivityChange = {})
            }
        }

        val density = compose.density
        val bitmap = bitmapOf(compose.activity)
        val badgeBounds = compose.onNodeWithTag("tier-badge-recommended", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val badgeFillPx = with(density) {
            bitmap.getPixel(
                (badgeBounds.left + 4.5.dp.toPx()).roundToInt(),
                ((badgeBounds.top + badgeBounds.bottom) / 2f).roundToInt(),
            )
        }

        assertEquals(
            "expected the Recommended badge to be filled with Teal, hiberna's affirmative colour",
            Teal.toArgb(),
            badgeFillPx,
        )
    }

    @Test
    fun `a Caution row shows an outline-only badge, never a solid fill that could collide with the row`() {
        compose.setContent {
            JinatraTheme {
                AppRow(row = cautionRow, onClick = {}, onActivityChange = {}, modifier = Modifier.testTag("row"))
            }
        }

        val density = compose.density
        val bitmap = bitmapOf(compose.activity)

        val rowBounds = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        val rowFillPx = with(density) {
            bitmap.getPixel(
                (rowBounds.left + 6.dp.toPx()).roundToInt(),
                (rowBounds.top + 6.dp.toPx()).roundToInt(),
            )
        }

        val badgeBounds = compose.onNodeWithTag("tier-badge-caution", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val badgeFillPx = with(density) {
            bitmap.getPixel(
                (badgeBounds.left + 4.5.dp.toPx()).roundToInt(),
                ((badgeBounds.top + badgeBounds.bottom) / 2f).roundToInt(),
            )
        }

        assertEquals("expected the Caution row itself to be filled with Mist", Mist.toArgb(), rowFillPx)
        assertEquals(
            "a Caution badge is outline-only by design (see the task report): its fill must " +
                "match whatever is already behind it, never introduce a second solid colour",
            rowFillPx,
            badgeFillPx,
        )
    }

    @Test
    fun `a Safe row shows no tier badge at all`() {
        compose.setContent {
            JinatraTheme {
                AppRow(row = safeRow, onClick = {}, onActivityChange = {})
            }
        }

        compose.onNodeWithTag("tier-badge-recommended", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("tier-badge-caution", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("sensitivity-chip", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a Recommended row never also shows the Caution or WILL_BREAK treatments`() {
        compose.setContent {
            JinatraTheme {
                AppRow(row = recommendedRow, onClick = {}, onActivityChange = {})
            }
        }

        compose.onNodeWithTag("tier-badge-caution", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("sensitivity-chip", useUnmergedTree = true).assertDoesNotExist()
    }
}
