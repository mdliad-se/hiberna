// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.privilege

import com.jinatra.hiberna.shell.ShellResult

/**
 * Every call into the Shizuku SDK lives behind this interface. Shizuku's
 * process API is a restricted member reached by reflection; confining it here
 * keeps that reflection in one file and keeps [ShizukuGate] testable on the JVM.
 *
 * ## Threading
 * **Every member of this interface except [addStateListener] and
 * [removeStateListener] performs a synchronous binder transaction.** None of
 * them may be read from the main thread, from a Compose composition, or from a
 * `derivedStateOf` - a dead or busy Shizuku service turns any of them into an
 * ANR. [ShizukuGate] is the only intended caller and it does all of it inside
 * `Dispatchers.IO`.
 */
internal interface ShizukuPlatform {

    /**
     * The Shizuku manager app (or Sui) is present on the device. Distinct from
     * the service running: a user can have Shizuku installed and stopped, and
     * a Sui user has a running service with no Shizuku package at all.
     *
     * Performs a PackageManager lookup; see the threading note on the
     * interface.
     */
    val isInstalled: Boolean

    /** Binder IPC. See the threading note on the interface. */
    val isBinderAlive: Boolean

    /**
     * Binder IPC. See the threading note on the interface.
     *
     * **Known SDK behaviour:** `Shizuku.checkSelfPermission()` caches a
     * granted result in a static field and returns it without re-querying
     * ever again. A permission revoked while this process is alive is
     * therefore invisible until the process restarts. Do not treat a `true`
     * here as proof that the next command will succeed - treat the command's
     * own exit code as the authority.
     */
    fun checkSelfPermission(): Boolean

    /**
     * Shows the Shizuku permission dialog. Asynchronous: the answer arrives
     * through [addStateListener], never as a return value. Throws inside the
     * SDK if the binder is dead, so callers must know the binder is alive.
     */
    fun requestPermission()

    /**
     * Register for binder-received / binder-dead / permission-result events.
     * Without this the common flow - open app, see "not running", start
     * Shizuku, come back - never updates and nothing ever reaches
     * [PrivilegeState.READY].
     *
     * [onChanged] may be invoked on the main thread; it must not block.
     * Implementations may deliver one event immediately on registration if the
     * binder is already available - the real one does, because a device where
     * Shizuku was already running produces no *change* to report.
     *
     * At most one listener is held at a time. Calling this again replaces the
     * previous listener rather than adding a second one or throwing - the real
     * implementation already does this (it un-registers before registering),
     * so a fake that throws on re-registration would certify behaviour no
     * device exhibits.
     */
    fun addStateListener(onChanged: () -> Unit)

    fun removeStateListener()

    /** Binder IPC, and blocking. See the threading note on the interface. */
    fun exec(command: List<String>): ShellResult
}
