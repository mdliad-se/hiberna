// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jinatra.hiberna.privilege.PrivilegeState
import com.jinatra.hiberna.ui.screens.gate.GateScreen
import com.jinatra.hiberna.ui.theme.Cream
import com.jinatra.hiberna.ui.theme.JinatraTheme
import kotlinx.coroutines.launch

/**
 * The real entry point Task 2 stubbed out. It shows [GateScreen] for every
 * [PrivilegeState] except [PrivilegeState.READY], which gets a clearly-marked
 * placeholder here - the app list itself arrives in Task 11.
 *
 * The one [com.jinatra.hiberna.privilege.ShizukuGate] for the process lives on
 * [AppContainer] (see its kdoc); this activity only ever reads it, never
 * constructs one, so there is exactly one set of Shizuku listeners no matter
 * how many times this activity is recreated.
 */
class MainActivity : ComponentActivity() {

    private val container get() = (application as HibernaApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JinatraTheme {
                val gate = container.gate
                val state by gate.state.collectAsStateWithLifecycle()
                when (state) {
                    PrivilegeState.READY -> ReadyPlaceholder()
                    else -> GateScreen(
                        state = state,
                        onRequest = gate::request,
                        onRefresh = { lifecycleScope.launch { gate.refresh() } },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have started Shizuku, or granted/revoked access, while
        // hiberna was backgrounded - the SDK's own listener covers changes
        // that happen while foregrounded, not this gap.
        lifecycleScope.launch { container.gate.refresh() }
    }
}

/** Task 11 replaces this with the real app list. */
@Composable
private fun ReadyPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .testTag("ready-placeholder"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Ready - app list arrives in Task 11",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
