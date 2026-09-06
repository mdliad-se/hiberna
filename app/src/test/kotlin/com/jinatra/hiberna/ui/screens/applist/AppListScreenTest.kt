// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.ui.theme.JinatraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs under Robolectric so it stays part of `:app:testDebugUnitTest` with no
 * device needed - same pattern as GateScreenTest.
 */
@RunWith(RobolectricTestRunner::class)
class AppListScreenTest {

    @get:Rule val compose = createComposeRule()

    private val gameRow = AppRowState(
        app = InstalledApp("com.example.game", "Game", 10456, isSystem = false, isEnabled = true),
        activity = BackgroundActivity.RESTRICTED,
        dataBlocked = false,
        sensitivity = Sensitivity.NONE,
    )

    private val smsRow = AppRowState(
        app = InstalledApp("com.example.sms", "Messages", 10500, isSystem = false, isEnabled = true),
        activity = BackgroundActivity.UNRESTRICTED,
        dataBlocked = false,
        sensitivity = Sensitivity.LIKELY_BREAKS,
    )

    @Test
    fun `lists every row's label and package name`() {
        compose.setContent {
            JinatraTheme {
                AppListScreen(
                    state = AppListState(rows = listOf(gameRow, smsRow)),
                    onQueryChange = {},
                    onActivityChange = { _, _ -> },
                    onRowClick = {},
                )
            }
        }

        compose.onNodeWithText("Game").assertIsDisplayed()
        compose.onNodeWithText("com.example.sms").assertIsDisplayed()
    }

    @Test
    fun `flags a sensitive app without spending the screen's one Signal highlight`() {
        // Sensitivity is per-row and can match many apps at once, but Signal
        // is reserved for one highlight per screen (see Tokens.kt) - already
        // spent, when present, on the error banner. The per-row flag must use
        // a different treatment so multiple sensitive apps never multiply it.
        compose.setContent {
            JinatraTheme {
                AppListScreen(
                    state = AppListState(rows = listOf(gameRow, smsRow), error = "could not read system state"),
                    onQueryChange = {},
                    onActivityChange = { _, _ -> },
                    onRowClick = {},
                )
            }
        }

        compose.onNodeWithText("May stop working if restricted").assertIsDisplayed()
        compose.onNodeWithText("could not read system state").assertIsDisplayed()
    }

    @Test
    fun `typing in the search field reports the new query`() {
        var lastQuery: String? = null
        compose.setContent {
            JinatraTheme {
                AppListScreen(
                    state = AppListState(rows = listOf(gameRow)),
                    onQueryChange = { lastQuery = it },
                    onActivityChange = { _, _ -> },
                    onRowClick = {},
                )
            }
        }

        compose.onNodeWithText("Search apps").performTextInput("game")

        assertEquals("game", lastQuery)
    }

    @Test
    fun `tapping a row's body reports a click for that package`() {
        var clicked: String? = null
        compose.setContent {
            JinatraTheme {
                AppListScreen(
                    state = AppListState(rows = listOf(gameRow)),
                    onQueryChange = {},
                    onActivityChange = { _, _ -> },
                    onRowClick = { clicked = it },
                )
            }
        }

        compose.onNodeWithText("Game").performClick()

        assertEquals("com.example.game", clicked)
    }

    @Test
    fun `tapping a different activity state edits that row in place`() {
        // This is the screen's whole reason to exist: real background state,
        // editable. Tapping a non-current state must report the change;
        // tapping the currently-active state must not (it is disabled, not a
        // redundant re-apply).
        var changed: Pair<String, BackgroundActivity>? = null
        compose.setContent {
            JinatraTheme {
                AppListScreen(
                    state = AppListState(rows = listOf(gameRow)), // RESTRICTED
                    onQueryChange = {},
                    onActivityChange = { pkg, activity -> changed = pkg to activity },
                    onRowClick = {},
                )
            }
        }

        compose.onNodeWithText("UNRESTRICTED").performClick()
        assertEquals("com.example.game" to BackgroundActivity.UNRESTRICTED, changed)

        changed = null
        compose.onNodeWithText("RESTRICTED").performClick() // already current - disabled
        assertNull(changed)
    }

    @Test
    fun `shows an empty state rather than a blank screen when nothing matches`() {
        compose.setContent {
            JinatraTheme {
                AppListScreen(
                    state = AppListState(rows = emptyList()),
                    onQueryChange = {},
                    onActivityChange = { _, _ -> },
                    onRowClick = {},
                )
            }
        }

        compose.onNodeWithText("No apps match that search.").assertIsDisplayed()
    }
}
