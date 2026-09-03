package com.jinatra.hiberna.shell

class ShizukuShellBackend : ShellBackend {
    override val isAvailable: Boolean = false
    override suspend fun exec(command: List<String>): ShellResult =
        throw NotImplementedError("implemented in Task 3")
}
