// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.theme

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bug 3 (device-confirmed, API 36): content rendered straight under the
 * status bar/clock, because nothing consumed the insets an edge-to-edge
 * window dispatches. [JinatraTheme] now applies `Modifier.windowInsetsPadding
 * (WindowInsets.safeDrawing)` to its content [Box] - see that file's kdoc.
 *
 * `@Config(qualifiers = "w360dp-h640dp")` and `@GraphicsMode(NATIVE)` both
 * matter here, per Task 11's carried-forward note: legacy Robolectric
 * graphics mode does not measure real font metrics or (relevant to this
 * file) drive a real layout/inset pass, so a test under the default graphics
 * mode can pass even when the modifier under test is deleted entirely.
 *
 * This dispatches a synthetic [WindowInsetsCompat] directly at the
 * `ComposeView` Compose itself creates (the direct child `setContent` adds
 * under the activity's content `FrameLayout`), rather than at
 * `window.decorView` - dispatching from the decor view was tried first and
 * measured no movement at all, because `ViewGroup`'s default
 * `dispatchApplyWindowInsets` only forwards insets to a child that itself
 * requested them AND is laid out to consume the window's real system-bar
 * space, neither of which a bare Robolectric-built `ComponentActivity`
 * arranges the way a real launched Activity's window would. Dispatching
 * straight at the `ComposeView` - the one view that actually registers the
 * `OnApplyWindowInsetsListener` `WindowInsets.safeDrawing` reads from - is
 * the direct, real mechanism `windowInsetsPadding` depends on, not a
 * shortcut around it: deleting `windowInsetsPadding` from `JinatraTheme`
 * makes this test fail (verified), so it is asserting the real behaviour,
 * not a tautology.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp")
class JinatraThemeInsetsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `JinatraTheme pushes content clear of a dispatched status-bar inset`() {
        compose.setContent {
            JinatraTheme {
                Box(modifier = Modifier.fillMaxSize().testTag("content"))
            }
        }
        compose.waitForIdle()

        val topBefore = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.top
        assertEquals(
            "sanity check: with no inset dispatched yet, content should start flush with the root",
            0f,
            topBefore,
            0.5f,
        )

        val contentRoot = compose.activity.findViewById<ViewGroup>(android.R.id.content)
        val composeView = contentRoot.getChildAt(0)
        val statusBarPx = 66
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarPx, 0, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(composeView, insets)
        compose.waitForIdle()

        val topAfter = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.top

        assertEquals(
            "expected the content box to be pushed down by exactly the dispatched status-bar " +
                "inset, so nothing renders underneath it",
            statusBarPx.toFloat(),
            topAfter,
            0.5f,
        )
    }
}
