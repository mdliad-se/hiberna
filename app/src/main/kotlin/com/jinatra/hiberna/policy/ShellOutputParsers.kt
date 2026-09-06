// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.policy

/**
 * Parsers for three undocumented shell formats. Each is written against a
 * fixture captured from a real device in Task 1, not against an assumption.
 *
 * Formats confirmed on a real device by the Task 1 spike - see
 * docs/spine/SPIKE-2026-09-02-privilege.md. Do not "simplify" these against
 * intuition; each guard below exists because the device does something a
 * reasonable person would not predict.
 *
 * `cmd appops query-op RUN_ANY_IN_BACKGROUND ignore` prints one bare package
 * per line. Empty has TWO representations: the literal "No operations." on a
 * pristine device, and a zero-byte response once an op has been set and
 * reverted.
 */
fun parseAppOpsRestricted(raw: String): Set<String> =
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        // "No operations." is what the command prints when nothing is
        // restricted. It contains a dot, so a naive dot check turns it into a
        // package name. Real package names never contain whitespace.
        .filter { line -> line.none(Char::isWhitespace) }
        .filter { it.contains('.') && !it.endsWith('.') }
        .toSet()

/**
 * `dumpsys deviceidle whitelist` prints comma-separated rows shaped
 * `<source>,<package>,<uid>`. Only the package column is of interest.
 */
fun parseDeviceIdleWhitelist(raw: String): Set<String> =
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split(',')
            when {
                parts.size >= 2 -> parts[1].trim().takeIf { it.contains('.') }
                line.contains('.') -> line
                else -> null
            }
        }
        .toSet()

/**
 * `cmd netpolicy list restrict-background-blacklist` prints a header line
 * followed by one uid per line.
 */
fun parseNetPolicyBlacklist(raw: String): Set<Int> {
    // The uids share the header line, space-separated with a trailing space:
    //   "Restrict background blacklisted UIDs: 10153 10220 "
    // and the empty case reads "...UIDs: none". A line-oriented parser
    // returns an empty set here every time, silently.
    //
    // No `missingDelimiterValue` override here: the stdlib default falls back
    // to the whole input when ':' is absent, which matters for callers (and
    // tests) that hand this a bare uid list with no header at all.
    val values = raw.substringAfter(':')
    if (values.isBlank()) return emptySet()
    return values.trim()
        .split(Regex("\\s+"))
        .mapNotNull { it.toIntOrNull() }
        .toSet()
}
