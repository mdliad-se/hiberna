// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Archivo, Inter and JetBrains Mono are not bundled yet. Until they are,
 * these weights resolve against the platform default font. Before release,
 * add the font files under app/src/main/res/font/ and wire them in here -
 * the brand requires Archivo for display and forbids falling back to a
 * rounded face.
 *
 * F5: this is the one [Surface] that paints [Cream] behind every screen.
 * Brand v1.1 is explicit that Cream is the app canvas and is "never white",
 * but before this fix nothing actually painted it - [GateScreen] drew its
 * own [Cream] background, `AppListScreen`/`AppDetailSheet`/`PresetScreen`
 * painted nothing at all, so everything past the gate rendered on the
 * platform's plain white window background regardless of what
 * `background = Cream` said below. Every one of this app's composition
 * roots (production in `MainActivity`, and every test in this suite) wraps
 * its content in [JinatraTheme] already, so putting the fill exactly here -
 * one [Surface]/[Modifier.fillMaxSize] at the single point every screen
 * passes through - paints the canvas everywhere at once, rather than
 * repeating `.background(Cream)` on each of `AppListScreen`,
 * `AppDetailSheet` and `PresetScreen` individually (and risking a future
 * fourth screen forgetting it). [GateScreen]'s own explicit
 * `.background(Cream)` is now redundant but harmless - painting the same
 * colour twice is a no-op, not a conflict.
 *
 * **Window insets:** content used to render straight under the status bar
 * (device-confirmed - the top of the app list sat beneath the clock) because
 * nothing here ever consumed the system bar/cutout insets Android dispatches
 * to an edge-to-edge window. The [Surface] above stays a plain
 * `fillMaxSize()` with no inset padding of its own, so the Cream canvas it
 * paints still reaches every edge - brand v1.1 wants Cream edge-to-edge, not
 * boxed in by bars. Only the inner content [Box] consumes
 * [WindowInsets.safeDrawing] (status/nav bars, display cutout, IME), at this
 * same single pass-through point, so every screen - [GateScreen],
 * `AppListScreen`, `AppDetailSheet`, `PresetScreen` - is pushed clear of the
 * status bar, the notch and the navigation bar without any of them handling
 * insets individually.
 */
@Composable
fun JinatraTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Teal,
        onPrimary = Paper,
        secondary = Product,
        onSecondary = Cream,
        background = Cream,
        onBackground = InkColor,
        surface = Paper,
        onSurface = InkColor,
        error = Signal,
        onError = InkColor,
    )

    val typography = Typography(
        headlineLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 34.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp),
        bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
        labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp),
    )

    MaterialTheme(colorScheme = colors, typography = typography) {
        Surface(modifier = Modifier.fillMaxSize(), color = Cream) {
            Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                content()
            }
        }
    }
}
