// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.apps

/**
 * A row for the app list. Deliberately holds no icon: a [android.graphics.drawable.Drawable]
 * in a data class kept inside a `StateFlow` would retain a bitmap for every installed
 * app on the device. Icons are loaded per-row by the UI layer instead.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
)
