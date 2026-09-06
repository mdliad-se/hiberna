// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Archivo, Inter and JetBrains Mono are not bundled yet. Until they are,
 * these weights resolve against the platform default font. Before release,
 * add the font files under app/src/main/res/font/ and wire them in here -
 * the brand requires Archivo for display and forbids falling back to a
 * rounded face.
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

    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
