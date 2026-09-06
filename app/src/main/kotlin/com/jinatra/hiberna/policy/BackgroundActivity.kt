// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

/**
 * The tri-state a user actually sees. Wording deliberately matches Android's
 * own battery-usage screen so the app never teaches a second vocabulary for the
 * same setting.
 */
enum class BackgroundActivity {
    /** appops `ignore`, not battery-whitelisted. */
    RESTRICTED,
    /** appops `allow`, not battery-whitelisted — the system decides. */
    OPTIMIZED,
    /** appops `allow` plus a battery whitelist entry. */
    UNRESTRICTED,
}
