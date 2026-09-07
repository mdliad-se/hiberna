// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.shell

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Negative exit codes this app synthesises when a command never produced one
 * of its own. A real process exit code is always >= 0, so these can never
 * collide with one.
 *
 * They are distinct on purpose: "shizuku is not running" and "the reflected
 * privileged entry point vanished in a minified build" are the same symptom to
 * a user and completely different bugs to fix, and a single `-1` for both makes
 * every field report undiagnosable.
 */
object ShellExit {
    /** The privilege gate is shut: no binder, or no permission. */
    const val UNAVAILABLE = -1

    /**
     * `Shizuku.newProcess` could not be resolved by reflection. In practice
     * this means R8 stripped or renamed it, i.e. a broken release build.
     */
    const val REFLECTION_UNAVAILABLE = -2

    /** The process could not be started, or died mid-read. */
    const val EXEC_FAILED = -3

    /** The command did not exit within the exec timeout and was destroyed. */
    const val TIMED_OUT = -4

    /** The calling thread was interrupted while waiting; interrupt restored. */
    const val INTERRUPTED = -5

    /** Human-readable name for logs and error strings. */
    fun describe(exitCode: Int): String = when (exitCode) {
        UNAVAILABLE -> "UNAVAILABLE"
        REFLECTION_UNAVAILABLE -> "REFLECTION_UNAVAILABLE"
        EXEC_FAILED -> "EXEC_FAILED"
        TIMED_OUT -> "TIMED_OUT"
        INTERRUPTED -> "INTERRUPTED"
        else -> "exit=$exitCode"
    }
}
