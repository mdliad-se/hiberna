// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.arch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * This project has a habit of turning prose constraints into tests. This is
 * the one guarding privilege: the whole reason the policy layer is testable
 * without a paired device is that exactly one package may reach a shell, and
 * exactly one may reach the Shizuku SDK. A stray import anywhere else silently
 * dissolves that boundary.
 */
class PrivilegeBoundaryTest {

    private val shizukuSdk = "rikka.shizuku"

    // rikka.shizuku is the SDK package this app calls into directly, but
    // moe.shizuku.server.IRemoteProcess and moe.shizuku.api.BinderContainer
    // are also on the compile classpath and reach the same remote-process
    // AIDL - a file outside privilege/ importing those would dissolve the
    // boundary just as much as importing rikka.shizuku would.
    private val forbiddenSdkPackages = listOf(shizukuSdk, "moe.shizuku")
    private val forbiddenSpawns = listOf("Runtime.getRuntime()", "ProcessBuilder")

    private val mainSources: File by lazy {
        val here = File("").absoluteFile
        listOf(
            File(here, "src/main/kotlin"),
            File(here, "app/src/main/kotlin"),
            File(here, "../app/src/main/kotlin"),
        ).firstOrNull { it.isDirectory }
            ?: error("cannot locate app/src/main/kotlin from $here")
    }

    private fun kotlinFiles(): List<File> =
        mainSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun relative(file: File): String =
        file.relativeTo(mainSources).invariantSeparatorsPath

    @Test
    fun `the scan actually sees the source tree`() {
        // A boundary test that matches nothing passes forever. Anchor it.
        val files = kotlinFiles().map { relative(it) }
        assertTrue("no Kotlin sources found under $mainSources", files.size >= 5)
        assertTrue(
            "expected the privilege package in the scan, got $files",
            files.any { it.startsWith("com/jinatra/hiberna/privilege/") },
        )
        assertTrue(
            "expected at least one Shizuku SDK reference inside privilege/",
            kotlinFiles().any {
                relative(it).startsWith("com/jinatra/hiberna/privilege/") &&
                    it.readText().contains(shizukuSdk)
            },
        )
    }

    @Test
    fun `only the privilege package references the Shizuku SDK`() {
        val offenders = kotlinFiles()
            .map { relative(it) to it.readText() }
            .filterNot { (path, _) -> path.startsWith("com/jinatra/hiberna/privilege/") }
            .filter { (_, text) -> forbiddenSdkPackages.any { text.contains(it) } }
            .map { (path, _) -> path }

        assertEquals(
            "$forbiddenSdkPackages may only be referenced from privilege/; found in $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `nothing in main spawns a process directly`() {
        // Shizuku runs commands as shell uid; Runtime.exec and ProcessBuilder
        // run them as this app, which cannot change another app's settings.
        // A silent fallback to either would look like it works and do nothing.
        val offenders = kotlinFiles()
            .map { relative(it) to it.readText() }
            .flatMap { (path, text) ->
                forbiddenSpawns.filter { text.contains(it) }.map { "$path uses $it" }
            }

        assertEquals(
            "all shell execution must go through ShellBackend; found $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the release manifest never declares INTERNET`() {
        val manifest = File(mainSources.parentFile, "AndroidManifest.xml")
        assertTrue("manifest not found at $manifest", manifest.isFile)
        assertTrue(
            "INTERNET must never be declared",
            !manifest.readText().contains("android.permission.INTERNET"),
        )
    }
}
