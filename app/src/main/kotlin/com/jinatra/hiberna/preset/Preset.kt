// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.preset

import com.jinatra.hiberna.policy.BackgroundActivity
import kotlinx.serialization.Serializable

/**
 * A named, reusable bulk policy. Stored as JSON (see [DataStorePresetRepository])
 * so a future export feature can hand a user this exact representation as a
 * file rather than maintaining a separate wire format.
 */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val backgroundActivity: BackgroundActivity,
    val restrictBackgroundData: Boolean,
    /** Defaults true so a bulk apply cannot silently break messaging or alarms. */
    val skipSensitive: Boolean = true,
)

val DEFAULT_PRESETS: List<Preset> = listOf(
    Preset("balanced", "Balanced", BackgroundActivity.OPTIMIZED, restrictBackgroundData = false),
    Preset("frugal", "Frugal", BackgroundActivity.RESTRICTED, restrictBackgroundData = false),
    Preset("offline", "Offline", BackgroundActivity.RESTRICTED, restrictBackgroundData = true),
)
