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
    /**
     * **Snapshot only — never use this to apply policy.**
     *
     * This is the uid observed at enumeration time. If the app is uninstalled and a
     * different app is later installed under the same package name, this value goes
     * stale while the row keeps showing the old label: the row silently starts pointing
     * at the wrong uid. Because this list is held in a `StateFlow`, that staleness can
     * persist for the lifetime of the process.
     *
     * Callers that need to act on a uid (e.g. applying a network policy) must call
     * [InstalledAppRepository.uidOf] with the package name at apply time instead of
     * reading this field.
     */
    val uid: Int,
    val isSystem: Boolean,
    /**
     * Mirrors [android.content.pm.ApplicationInfo.enabled]. This single flag collapses
     * three distinct states — user-disabled, device-admin-disabled, and
     * `DISABLED_UNTIL_USED` (a bundled app not yet activated) — into one boolean, so it
     * is **not** a reliable signal of user intent. Consumers that want to filter or
     * annotate on this should do so knowing that a `false` value does not necessarily
     * mean "the user disabled this app".
     */
    val isEnabled: Boolean,
)
