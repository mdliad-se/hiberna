// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import com.jinatra.hiberna.privilege.RealShizukuPlatform
import com.jinatra.hiberna.ui.screens.gate.GateScreen
import com.jinatra.hiberna.ui.theme.Cream
import com.jinatra.hiberna.ui.theme.JinatraTheme
import kotlinx.coroutines.launch

/**
 * Where F-Droid (or the browser it hands the intent to) lists the Shizuku
 * manager app. Built from [RealShizukuPlatform.SHIZUKU_PACKAGE] - the one
 * already-defined package id - rather than a second literal copy of it here.
 */
internal val SHIZUKU_FDROID_URL =
    "https://f-droid.org/packages/${RealShizukuPlatform.SHIZUKU_PACKAGE}/"

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
                        onInstall = ::openShizukuInstallPage,
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

    /**
     * Sends the user to Shizuku's F-Droid listing. Needs no permission of its
     * own: `ACTION_VIEW` hands the networking to whatever app - an installed
     * F-Droid client, or the browser - registers for the URL, so this app
     * never has to (and per `ManifestPermissionsTest`, never may) declare
     * INTERNET itself.
     *
     * A device with neither an F-Droid client nor a browser cannot handle
     * this intent; [ActivityNotFoundException] is caught so that leaves the
     * user back on the gate screen rather than crashing the app.
     */
    private fun openShizukuInstallPage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_FDROID_URL))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity can handle $SHIZUKU_FDROID_URL", e)
        }
    }

    private companion object {
        private const val TAG = "HibernaGate"
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
