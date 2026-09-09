// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.metrics

/**
 * Per-app battery use, as Android estimates it.
 *
 * These are the platform's own estimates and may disagree with what Settings
 * shows. They are attributed to **uids, not packages**: where several packages
 * share a uid, one figure covers all of them, and the UI says so rather than
 * implying the number belongs to one package.
 */
data class BatteryPowerUse(
    /** Estimated drain since last charge, the denominator for [percentOf]. */
    val computedDrainMah: Double,
    /** Battery capacity the estimate was computed against. */
    val capacityMah: Double,
    /** How long the device has been off the charger - the window these figures cover. */
    val timeOnBatteryMillis: Long,
    val mahByUid: Map<Int, Double>,
) {
    /**
     * [uid]'s share of the drain, or null when this capture said nothing about
     * that uid. Null is not zero: "we have no figure for this app" and "this
     * app used no battery" must stay different answers, or the UI will present
     * an unmeasured app as the cleanest one on the device.
     */
    fun percentOf(uid: Int): Double? {
        val mah = mahByUid[uid] ?: return null
        if (computedDrainMah <= 0.0) return null
        return mah / computedDrainMah * 100.0
    }
}

/**
 * Parses `dumpsys batterystats --charged`.
 *
 * **Returns null on anything it does not fully recognise.** The output format
 * is undocumented and moves between Android versions and vendors, so this
 * parser is written to fail loudly-by-omission rather than to guess: a battery
 * figure that is quietly wrong is worse than one that is quietly missing,
 * because a user acts on it. Verified against Android 17 (API 37) on a Pixel
 * 10 Pro; the fixture is `app/src/test/resources/fixtures/batterystats_charged.txt`.
 *
 * Shape it expects, inside `Estimated power use (mAh):`:
 * ```
 *   Estimated power use (mAh):
 *     Capacity: 4772, Computed drain: 355, actual drain: 355
 *     Global
 *     screen: 17.5 apps: 17.5
 *   UID u0a644: 39.8 fg: 5.54 (5m 20s 905ms) bg: 15.9 (28m 53s 32ms)
 *   UID 1000: 16.2 bg: 16.2
 * ```
 */
fun parseBatteryPowerUse(raw: String): BatteryPowerUse? {
    val sectionStart = raw.lineSequence().indexOfFirst { SECTION_HEADER.containsMatchIn(it) }
    if (sectionStart < 0) return null

    val timeOnBattery = parseTimeOnBattery(raw) ?: return null

    val lines = raw.lines()
    val drain = DRAIN.find(raw) ?: return null
    val capacity = drain.groupValues[1].toDoubleOrNull() ?: return null
    val computed = drain.groupValues[2].toDoubleOrNull() ?: return null
    // A per-uid figure with no total cannot become a percentage, and inventing
    // a denominator would be a fabricated number.
    if (computed <= 0.0) return null

    val byUid = mutableMapOf<Int, Double>()
    for (line in lines.drop(sectionStart)) {
        val match = UID_LINE.find(line) ?: continue
        // An unrecognised uid form - an isolated process ("u0i42"), or
        // whatever a later release invents - is skipped rather than failing
        // the read. One unknown line must not cost the user every other app's
        // number, and it cannot be mapped to a package anyway.
        val uid = parseUid(match.groupValues[1]) ?: continue
        val mah = match.groupValues[2].toDoubleOrNull() ?: continue
        byUid[uid] = mah
    }

    // On a real device this is never empty: the platform's own uids always
    // draw power. Empty therefore means the format moved, not that nothing
    // used any battery - the same reasoning PolicyReader applies to an empty
    // deviceidle whitelist.
    if (byUid.isEmpty()) return null

    return BatteryPowerUse(
        computedDrainMah = computed,
        capacityMah = capacity,
        timeOnBatteryMillis = timeOnBattery,
        mahByUid = byUid,
    )
}

/**
 * `u0a644` -> 10644, `u10a5` -> 1010005, `1000` -> 1000.
 *
 * Returns null for any other form. The user segment is honoured rather than
 * assumed zero: attributing a work-profile app's drain to whatever package
 * holds that appId in the primary user would name the wrong app.
 */
private fun parseUid(token: String): Int? {
    token.toIntOrNull()?.let { return it }
    val app = APP_UID.matchEntire(token) ?: return null
    val userId = app.groupValues[1].toIntOrNull() ?: return null
    val appId = app.groupValues[2].toIntOrNull() ?: return null
    return userId * PER_USER_RANGE + FIRST_APPLICATION_UID + appId
}

/** `Time on battery: 51m 46s 370ms (100.0%) realtime, ...` */
private fun parseTimeOnBattery(raw: String): Long? {
    val match = TIME_ON_BATTERY.find(raw) ?: return null
    val duration = match.groupValues[1]
    var total = 0L
    var sawUnit = false
    for (part in DURATION_PART.findAll(duration)) {
        val value = part.groupValues[1].toLongOrNull() ?: return null
        total += when (part.groupValues[2]) {
            "d" -> value * 86_400_000L
            "h" -> value * 3_600_000L
            "m" -> value * 60_000L
            "s" -> value * 1_000L
            "ms" -> value
            else -> return null
        }
        sawUnit = true
    }
    return if (sawUnit) total else null
}

/** Mirrors `android.os.Process.FIRST_APPLICATION_UID`, not linked to avoid a framework import here. */
private const val FIRST_APPLICATION_UID = 10000

/** Mirrors `android.os.UserHandle.PER_USER_RANGE`. */
private const val PER_USER_RANGE = 100000

private val SECTION_HEADER = Regex("""^\s*Estimated power use \(mAh\):""")
private val DRAIN = Regex("""Capacity:\s*([0-9.]+),\s*Computed drain:\s*([0-9.]+)""")
private val TIME_ON_BATTERY = Regex("""Time on battery:\s*([0-9dhms ]+?)\s*\([0-9.]+%\)\s*realtime""")
private val DURATION_PART = Regex("""(\d+)(ms|[dhms])""")
private val UID_LINE = Regex("""^\s*UID\s+(\S+):\s*([0-9.]+)""")
private val APP_UID = Regex("""^u(\d+)a(\d+)$""")
