// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.shell

/**
 * The only seam in this app that may touch a shell.
 *
 * Nothing outside the `shell` package may call Shizuku, `Runtime.exec`, or a
 * process builder directly. That rule is what makes the policy layer testable
 * without a paired device, and it is asserted at boot in release builds.
 */
interface ShellBackend {
    val isAvailable: Boolean
    suspend fun exec(command: List<String>): ShellResult
}
