// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

import kotlinx.serialization.Serializable

/**
 * A package's desired state. Keyed on package name; uid is never stored here
 * because uids are reassigned on reinstall and a stale uid silently points at
 * a different app. See [com.jinatra.hiberna.apps.InstalledApp.uid] for the
 * full history of that bug class.
 */
@Serializable
data class AppPolicy(
    val packageName: String,
    val backgroundActivity: BackgroundActivity,
    val restrictBackgroundData: Boolean,
)
