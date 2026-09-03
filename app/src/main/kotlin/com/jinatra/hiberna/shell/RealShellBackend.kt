package com.jinatra.hiberna.shell

import com.jinatra.hiberna.privilege.RealShizukuPlatform
import com.jinatra.hiberna.privilege.ShizukuPlatform

/**
 * Production wiring for the one seam allowed to touch a shell.
 *
 * Exists so nothing outside `shell/` and `privilege/` has to name a Shizuku
 * type to get a working backend - which is what
 * `PrivilegeBoundaryTest` enforces.
 */
fun realShellBackend(context: android.content.Context): ShellBackend =
    ShizukuShellBackend(RealShizukuPlatform(context))

/**
 * The [ShizukuPlatform] behind a backend, if it has one.
 *
 * Callers use this to share the single platform instance rather than
 * constructing a second one, which would mean a second set of SDK listeners
 * and a second answer to the same question.
 */
internal fun ShellBackend.shizukuPlatformOrNull(): ShizukuPlatform? =
    (this as? ShizukuShellBackend)?.platform
