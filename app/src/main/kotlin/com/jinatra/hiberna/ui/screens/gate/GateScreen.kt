// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.gate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.privilege.PrivilegeState
import com.jinatra.hiberna.ui.components.BrutalButton
import com.jinatra.hiberna.ui.components.brutalSurface
import com.jinatra.hiberna.ui.theme.Cream
import com.jinatra.hiberna.ui.theme.Paper
import com.jinatra.hiberna.ui.theme.Product
import com.jinatra.hiberna.ui.theme.ShadowMd
import com.jinatra.hiberna.ui.theme.Teal

/**
 * The first screen most users see. Every [PrivilegeState] but [PrivilegeState.READY]
 * lands here; the screen's whole job is naming the one next action that moves
 * the user closer to READY. Copy never blames the user - they have not done
 * anything wrong, they have just not finished setup yet.
 *
 * [PrivilegeState.CHECKING] is a real, exhaustively-handled branch, not an
 * afterthought: see [CheckingIndicator] for why it renders a static brand-bar
 * rather than a spinner or nothing at all.
 */
@Composable
fun GateScreen(
    state: PrivilegeState,
    onRequest: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Cream)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Column(
            modifier = Modifier.brutalSurface(fill = Paper, shadow = ShadowMd).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = headline(state), style = MaterialTheme.typography.titleLarge)
            Text(text = explanation(state), style = MaterialTheme.typography.bodyLarge)

            when (state) {
                PrivilegeState.CHECKING -> CheckingIndicator()
                PrivilegeState.PERMISSION_DENIED ->
                    BrutalButton(text = "Grant access", onClick = onRequest, fill = Product)
                PrivilegeState.SHIZUKU_ABSENT, PrivilegeState.SERVICE_NOT_RUNNING ->
                    BrutalButton(text = "Check again", onClick = onRefresh, fill = Product)
                PrivilegeState.READY -> Unit
            }
        }
    }
}

private fun headline(state: PrivilegeState): String = when (state) {
    PrivilegeState.CHECKING -> "Checking Shizuku"
    PrivilegeState.SHIZUKU_ABSENT -> "Shizuku is not installed"
    PrivilegeState.SERVICE_NOT_RUNNING -> "Start Shizuku to continue"
    PrivilegeState.PERMISSION_DENIED -> "Grant hiberna access to continue"
    PrivilegeState.READY -> "Ready"
}

private fun explanation(state: PrivilegeState): String = when (state) {
    PrivilegeState.CHECKING ->
        "Looking for Shizuku on this device. This should only take a moment."
    PrivilegeState.SHIZUKU_ABSENT ->
        "hiberna changes background settings through Shizuku. Install Shizuku " +
            "from F-Droid or GitHub, then come back here."
    PrivilegeState.SERVICE_NOT_RUNNING ->
        "Open Shizuku and start it. On most phones that means turning on " +
            "Wireless debugging in Developer options, then pairing Shizuku with " +
            "the code it shows you. No computer needed."
    PrivilegeState.PERMISSION_DENIED ->
        "Shizuku is running. Grant hiberna access and it can start reading and " +
            "changing background settings."
    PrivilegeState.READY -> "Shizuku is running and hiberna has access."
}

/**
 * What CHECKING renders, and why it is not a spinner and not empty space.
 *
 * The brand forbids spinners outright; the one motion-free progress shape it
 * allows is a squared, Ink-bordered bar filled with Teal. A genuinely
 * indeterminate *animated* bar was rejected for two reasons that both point
 * the same way:
 *
 * 1. CHECKING is normally on screen for single-digit milliseconds - the time
 *    for one binder round trip. Anything that animates will, on the one
 *    frame it actually gets drawn, look like a stray artifact rather than
 *    communicate progress; a static shape cannot glitch because it has no
 *    motion to be caught mid-cycle.
 * 2. There is no real percentage to show - only "waiting on IPC" - so an
 *    indeterminate slide would be decorating a binary wait with false
 *    precision, and would need [Modifier.testTag]'s animation to be paused
 *    by hand in every test that touches this screen.
 *
 * It still has to survive the state getting stuck (a dead listener, a bug):
 * the headline and explanation above this composable already say what is
 * happening, and this bar gives that a persistent visual anchor so a stuck
 * CHECKING reads as "still working" rather than a frozen, blank screen.
 */
@Composable
private fun CheckingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .testTag("gate-progress")
            .fillMaxWidth()
            .height(16.dp)
            .brutalSurface(fill = Paper, shadow = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.55f)
                .background(Teal),
        )
    }
}
