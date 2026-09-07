// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
        // tapping the currently-active state must not - it stays enabled and
        // announced as selected (see BrutalButton's `isSelected`), it is just
        // a no-op tap rather than a redundant re-apply.
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

        compose.onNodeWithText("Unrestricted").performClick()
        assertEquals("com.example.game" to BackgroundActivity.UNRESTRICTED, changed)

        changed = null
        compose.onNodeWithText("Restricted").performClick() // already current - no-op tap
        assertNull(changed)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the widest activity label lays out on a single line at 360dp`() {
        // A real measurement, not arithmetic: pin the narrowest phone width
        // Robolectric supports configuring directly, then compare the
        // picker's actual laid-out label height against a reference `Text`
        // using the same style with no width constraint at all. A
        // single-line text's height is determined by its style's font
        // metrics, not by which word is drawn, so a differently-worded
        // reference (avoiding a duplicate-text match against the picker's
        // own "Unrestricted" button) still gives a valid single-line
        // baseline. If the picker's copy ever force-wraps mid-word again,
        // its label height will be roughly double (or triple) this reference
        // value and the assertion below will catch it. A test rule can only
        // call setContent once, so both are composed together in one tree.
        //
        // @GraphicsMode(NATIVE) matters here, not just style: under
        // Robolectric's default (legacy) graphics mode, text is not measured
        // against real font metrics at all, so a wrap this test is meant to
        // catch does not reproduce - confirmed by temporarily reverting this
        // fix (20.dp content padding, 16.dp gap, ShadowSm, bodyLarge, no
        // maxLines) under legacy mode: it stayed falsely green (constrained
        // height matched the single-line reference regardless). The same
        // revert under NATIVE mode failed for real: singleLine=19,
        // constrained=57 (exactly 3 force-broken lines) - see
        // AppRowColorTest's doc for why this codebase already needs NATIVE
        // mode for real pixel/layout fidelity.
        compose.setContent {
            JinatraTheme {
                Column {
                    Text(
                        text = "Reference",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("single-line-reference"),
                    )
                    AppListScreen(
                        state = AppListState(rows = listOf(gameRow)), // RESTRICTED, so "Unrestricted" is a live option
                        onQueryChange = {},
                        onActivityChange = { _, _ -> },
                        onRowClick = {},
                    )
                }
            }
        }

        val singleLineHeight = compose.onNodeWithTag("single-line-reference").fetchSemanticsNode().size.height
        // useUnmergedTree = true: the button is itself a clickable (a
        // semantics merging boundary), so the *merged* node for its text
        // reports the whole button's bounds (text plus contentPadding), not
        // the Text's own tight bounds - the same reason AppRowColorTest
        // needs it for the sensitivity chip.
        val constrainedHeight = compose.onNodeWithText("Unrestricted", useUnmergedTree = true)
            .fetchSemanticsNode().size.height

        assertEquals(
            "expected the widest activity-picker label (\"Unrestricted\") to lay out at its " +
                "unconstrained single-line height at 360dp, not wrapped",
            singleLineHeight,
            constrainedHeight,
        )
    }

    @Test
    fun `tapping Presets reports the request rather than only being decorative`() {
        // Task 14: the preset screen exists and is fully tested (PresetScreenTest)
        // but was unreachable from anywhere a user could actually tap - this is
        // the one entry point into it.
        var opened = 0
        compose.setContent {
            JinatraTheme {
                AppListScreen(
                    state = AppListState(rows = listOf(gameRow)),
                    onQueryChange = {},
                    onActivityChange = { _, _ -> },
                    onRowClick = {},
                    onOpenPresets = { opened++ },
                )
            }
        }

        compose.onNodeWithText("Presets").performClick()

        assertEquals(1, opened)
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

    @Test
    fun `renders the bulk-apply summary instead of dropping it on the floor`() {
        // F1: AppListState.bulkSummary was computed, tested and had no
        // consumer in this screen - AppListScreen took no summary parameter
        // at all. A user who bulk-applied a preset across a hundred apps saw
        // the selection clear with no word on what happened.
        var dismissed = false
        compose.setContent {
            JinatraTheme {
                AppListScreen(
                    state = AppListState(rows = listOf(gameRow), bulkSummary = "Changed 1 app."),
                    onQueryChange = {},
                    onActivityChange = { _, _ -> },
                    onRowClick = {},
                    onDismissBulkSummary = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Changed 1 app.").assertIsDisplayed()

        compose.onNodeWithText("Dismiss").performClick()

        assertEquals(true, dismissed)
    }

    @Test
    fun `shows no bulk-apply summary when there is nothing to report`() {
        compose.setContent {
            JinatraTheme {
                AppListScreen(
                    state = AppListState(rows = listOf(gameRow), bulkSummary = null),
                    onQueryChange = {},
                    onActivityChange = { _, _ -> },
                    onRowClick = {},
                )
            }
        }

        compose.onNodeWithText("Dismiss").assertDoesNotExist()
    }
}
