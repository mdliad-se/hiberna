// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jinatra.hiberna.privilege.PrivilegeState
import com.jinatra.hiberna.ui.screens.gate.GateScreen
import com.jinatra.hiberna.ui.theme.JinatraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs the brief's connectedDebugAndroidTest spec under Robolectric instead,
 * so it stays part of `:app:testDebugUnitTest` and needs no device - see
 * .spine/task-9-report.md for why BrutalButtonTest does the same. Expanded
 * from the brief's 3 cases to cover all 5 PrivilegeState values, since Task
 * 3's review redesigned the enum to 5 values (CHECKING, SHIZUKU_ABSENT added).
 */
@RunWith(RobolectricTestRunner::class)
class GateScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun serviceNotRunningExplainsWirelessDebugging() {
        compose.setContent {
            JinatraTheme {
                GateScreen(PrivilegeState.SERVICE_NOT_RUNNING, onRequest = {}, onRefresh = {}, onInstall = {})
            }
        }

        compose.onNodeWithText("Wireless debugging", substring = true).assertIsDisplayed()
    }

    @Test
    fun permissionDeniedOffersTheRequestAction() {
        var requests = 0
        compose.setContent {
            JinatraTheme {
                GateScreen(
                    PrivilegeState.PERMISSION_DENIED,
                    onRequest = { requests++ },
                    onRefresh = {},
                    onInstall = {},
                )
            }
        }

        compose.onNodeWithText("Grant access").performClick()

        assertEquals(1, requests)
    }

    @Test
    fun serviceNotRunningOffersRetryRatherThanRequest() {
        var refreshes = 0
        compose.setContent {
            JinatraTheme {
                GateScreen(
                    PrivilegeState.SERVICE_NOT_RUNNING,
                    onRequest = {},
                    onRefresh = { refreshes++ },
                    onInstall = {},
                )
            }
        }

        compose.onNodeWithText("Check again").performClick()

        assertEquals(1, refreshes)
    }

    @Test
    fun shizukuAbsentTellsUserToInstallRatherThanStart() {
        // A review finding: SHIZUKU_ABSENT and SERVICE_NOT_RUNNING must not
        // collapse into the same message. A user with no Shizuku package
        // cannot "start" a service they do not have installed. Matched
        // against the full explanation phrase, not just "Install Shizuku",
        // because F1 gave SHIZUKU_ABSENT its own "Install Shizuku" button
        // too - a bare substring match would now hit both nodes.
        compose.setContent {
            JinatraTheme {
                GateScreen(PrivilegeState.SHIZUKU_ABSENT, onRequest = {}, onRefresh = {}, onInstall = {})
            }
        }

        compose.onNodeWithText("Install Shizuku from F-Droid", substring = true).assertIsDisplayed()
    }

    @Test
    fun shizukuAbsentOffersRefreshNotRequest() {
        var refreshes = 0
        compose.setContent {
            JinatraTheme {
                GateScreen(
                    PrivilegeState.SHIZUKU_ABSENT,
                    onRequest = {},
                    onRefresh = { refreshes++ },
                    onInstall = {},
                )
            }
        }

        compose.onNodeWithText("Check again").performClick()

        assertEquals(1, refreshes)
    }

    @Test
    fun shizukuAbsentOffersInstallAsItsPrimaryAction() {
        // F1: a user with no Shizuku installed cannot resolve this state by
        // retrying alone, so it must get an action of its own that actually
        // leads somewhere, not just "Check again" shared with
        // SERVICE_NOT_RUNNING.
        var installs = 0
        compose.setContent {
            JinatraTheme {
                GateScreen(
                    PrivilegeState.SHIZUKU_ABSENT,
                    onRequest = {},
                    onRefresh = {},
                    onInstall = { installs++ },
                )
            }
        }

        compose.onNodeWithText("Install Shizuku").performClick()

        assertEquals(1, installs)
    }

    @Test
    fun checkingNeverRendersABlankScreen() {
        // CHECKING has no IPC result yet. Whatever it renders must be visible
        // copy plus the brand's squared progress bar, not an empty Column -
        // otherwise a gate that gets stuck in CHECKING (a bug, a dead
        // listener) reads to the user as a frozen, blank app.
        compose.setContent {
            JinatraTheme {
                GateScreen(PrivilegeState.CHECKING, onRequest = {}, onRefresh = {}, onInstall = {})
            }
        }

        compose.onNodeWithText("Checking", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("gate-progress").assertIsDisplayed()
    }

    @Test
    fun readyRendersWithoutOfferingAnAction() {
        // MainActivity never routes READY through GateScreen, but the `when`
        // must still be exhaustive and must not crash if it is ever called
        // this way directly (e.g. a preview). Asserts the absence of any
        // clickable action node, not just that "Ready" text is present - the
        // name promises no action, so the test must check for one.
        compose.setContent {
            JinatraTheme {
                GateScreen(PrivilegeState.READY, onRequest = {}, onRefresh = {}, onInstall = {})
            }
        }

        compose.onNodeWithText("Ready", substring = true).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}
