// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.shell

class FakeShellBackend(
    override val isAvailable: Boolean = true,
) : ShellBackend {

    private val scripted = linkedMapOf<String, ShellResult>()
    private val _executed = mutableListOf<List<String>>()
    val executed: List<List<String>> get() = _executed.toList()

    fun script(match: String, result: ShellResult) {
        scripted[match] = result
    }

    override suspend fun exec(command: List<String>): ShellResult {
        _executed += command
        val joined = command.joinToString(" ")
        val hit = scripted.entries.firstOrNull { joined.contains(it.key) }
        return hit?.value
            ?: ShellResult(127, "", "unscripted command in FakeShellBackend: $joined")
    }
}
