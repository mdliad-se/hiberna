// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Jinatra Brand Guidelines v1.1. Source of truth: ../brand/jinatra.tokens.css
// and ../brand/README.md - values below are copied verbatim from there.
val Cream = Color(0xFFFFEACF) // app canvas. never white
val Paper = Color(0xFFFFFFFF) // card + input fill only, always inside an Ink border
val Teal = Color(0xFF0A756C) // primary buttons, active nav, table headers, links
val TealDeep = Color(0xFF06554F)
val Mist = Color(0xFFE0F0EE) // secondary surfaces, table stripes, hover fills
val InkColor = Color(0xFF1A1A1A) // every border, every shadow, all body text
val Signal = Color(0xFFFF6B35) // focus rings, warnings, ONE highlight per screen

// hiberna's product colour (Night Indigo) - colours the app icon container and
// this app's own accent surfaces. Never replaces Teal for primary actions.
val Product = Color(0xFF2F2BD1)
val ProductDeep = Color(0xFF211E9B)

val BorderWidth = 3.dp
val ShadowSm = 3.dp // controls
val ShadowMd = 6.dp // cards
val ShadowLg = 10.dp // one hero element per view
