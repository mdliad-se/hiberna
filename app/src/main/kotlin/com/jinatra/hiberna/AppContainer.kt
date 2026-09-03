package com.jinatra.hiberna

import android.content.Context
import com.jinatra.hiberna.shell.ShellBackend

/**
 * Manual dependency container. Hilt was considered and rejected: one module,
 * few dependencies, and Compose's `viewModel { }` factory covers the only
 * awkward case. Revisit if the module count grows.
 */
class AppContainer(
    val context: Context,
    val shell: ShellBackend,
)
