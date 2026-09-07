// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jinatra.hiberna.apps.InstalledApp
import com.jinatra.hiberna.guardrail.Sensitivity
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.ui.screens.applist.AppRowState
import com.jinatra.hiberna.ui.theme.JinatraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Runs under Robolectric so it stays part of `:app:testDebugUnitTest` with no
 * device needed - the brief's `connectedDebugAndroidTest` spec predates
 * GateScreenTest/BulkBarTest moving this pattern to `src/test`; see those
 * files' docs for why.
 *
 * Class-level `w360dp-h640dp`: Robolectric's unspecified default is a
 * 320x470dp window (confirmed by direct measurement) - shorter than any
 * shipping Android device, and too short for this sheet's tallest case (a
 * sensitive row's explanation box, picker, switch and button all at once)
 * once Task 2's own top bar/close affordance added its height on top. See
 * `AppListScreenTest`'s doc for the same reasoning applied there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class AppDetailSheetTest {

    @get:Rule val compose = createComposeRule()

    private val sensitiveRow = AppRowState(
        app = InstalledApp("com.example.sms", "Messages", 10500, isSystem = false, isEnabled = true),
        activity = BackgroundActivity.OPTIMIZED,
        dataBlocked = false,
        sensitivity = Sensitivity.LIKELY_BREAKS,
    )

    private val ordinaryRow = AppRowState(
        app = InstalledApp("com.example.game", "Game", 10456, isSystem = false, isEnabled = true),
        activity = BackgroundActivity.OPTIMIZED,
        dataBlocked = false,
        sensitivity = Sensitivity.NONE,
    )

    @Test
    fun `explains why the app is flagged, naming the actual consequence`() {
        compose.setContent {
            JinatraTheme {
                AppDetailSheet(
                    row = sensitiveRow, overridden = false,
                    onActivityChange = {}, onDataChange = {},
                    onOverrideChange = {}, onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("notifications", substring = true).assertIsDisplayed()
    }

    @Test
    fun `says nothing about being flagged for an unflagged app`() {
        // No explanation, no override switch, when there is nothing to
        // override - a "why is this flagged" box for a row that isn't
        // flagged would just be confusing noise.
        compose.setContent {
            JinatraTheme {
                AppDetailSheet(
                    row = ordinaryRow, overridden = false,
                    onActivityChange = {}, onDataChange = {},
                    onOverrideChange = {}, onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("notifications", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Include it in bulk changes anyway").assertDoesNotExist()
    }

    @Test
    fun `offers each of the three activity states`() {
        val chosen = mutableListOf<BackgroundActivity>()
        compose.setContent {
            JinatraTheme {
                AppDetailSheet(
                    row = sensitiveRow, overridden = false,
                    onActivityChange = { chosen += it }, onDataChange = {},
                    onOverrideChange = {}, onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("Restricted").performClick()
        compose.onNodeWithText("Unrestricted").performClick()

        assertEquals(
            listOf(BackgroundActivity.RESTRICTED, BackgroundActivity.UNRESTRICTED),
            chosen,
        )
    }

    @Test
    fun `tapping the already-current activity state is a no-op`() {
        // The row starts OPTIMIZED - tapping "Optimized" again must not
        // report a redundant re-apply. Matches the same contract AppRow's
        // own picker already guards (AppListScreenTest).
        var changed: BackgroundActivity? = null
        compose.setContent {
            JinatraTheme {
                AppDetailSheet(
                    row = sensitiveRow, overridden = false,
                    onActivityChange = { changed = it }, onDataChange = {},
                    onOverrideChange = {}, onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("Optimized").performClick()

        assertEquals(null, changed)
    }

    @Test
    fun `toggling the override switch reports the change`() {
        var overridden: Boolean? = null
        compose.setContent {
            JinatraTheme {
                AppDetailSheet(
                    row = sensitiveRow, overridden = false,
                    onActivityChange = {}, onDataChange = {},
                    onOverrideChange = { overridden = it }, onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("Include it in bulk changes anyway").performClick()

        assertEquals(true, overridden)
    }

    @Test
    fun `toggling background data reports the change`() {
        var blocked: Boolean? = null
        compose.setContent {
            JinatraTheme {
                AppDetailSheet(
                    row = sensitiveRow, overridden = false,
                    onActivityChange = {}, onDataChange = { blocked = it }, onOverrideChange = {},
                    onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("Block background mobile data").performClick()

        assertEquals(true, blocked)
    }

    @Test
    fun `open in settings fires the callback rather than building its own intent`() {
        // This composable never touches an Intent itself - see the kdoc for
        // why: it is a plain, unprivileged Intent that the hosting Activity
        // owns, following MainActivity.openShizukuInstallPage's shape.
        var opened = 0
        compose.setContent {
            JinatraTheme {
                AppDetailSheet(
                    row = sensitiveRow, overridden = false,
                    onActivityChange = {}, onDataChange = {},
                    onOverrideChange = {}, onOpenSettings = { opened++ },
                )
            }
        }

        compose.onNodeWithText("Open in Settings").performClick()

        assertEquals(1, opened)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the widest activity label lays out on a single line inside the sheet at 360dp`() {
        // Mirrors AppListScreenTest's own measurement of this exact picker
        // inside AppRow (see that test's doc for the full NATIVE-mode
        // rationale: Robolectric's default legacy graphics mode does not
        // measure real font metrics, so a wrap this test exists to catch
        // would not reproduce under it). This is the sheet's copy of the
        // same picker, which ActivityPicker's own kdoc notes is *less*
        // horizontally squeezed than AppRow's (roughly 48dp of combined
        // padding here versus roughly 64dp there) - a real measurement
        // rather than trusting that arithmetic.
        compose.setContent {
            JinatraTheme {
                Column {
                    Text(
                        text = "Reference",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("single-line-reference"),
                    )
                    AppDetailSheet(
                        row = sensitiveRow, overridden = false,
                        onActivityChange = {}, onDataChange = {},
                        onOverrideChange = {}, onOpenSettings = {},
                    )
                }
            }
        }

        val singleLineHeight =
            compose.onNodeWithTag("single-line-reference").fetchSemanticsNode().size.height
        // useUnmergedTree = true: the button is itself a clickable (a
        // semantics merging boundary), so the merged node for its text would
        // report the whole button's bounds (text plus contentPadding), not
        // the Text's own tight bounds - same reason AppListScreenTest needs
        // it for the identical assertion on AppRow's copy of this picker.
        val constrainedHeight = compose.onNodeWithText("Unrestricted", useUnmergedTree = true)
            .fetchSemanticsNode().size.height

        assertEquals(
            "expected the widest activity-picker label (\"Unrestricted\") to lay out at its " +
                "unconstrained single-line height at 360dp inside AppDetailSheet, not wrapped",
            singleLineHeight,
            constrainedHeight,
        )
    }

    @Test
    fun `never uses Hibernate as a UI verb`() {
        // Standing guard on a Global Constraint: Android ships its own App
        // Hibernation feature that does something different.
        compose.setContent {
            JinatraTheme {
                AppDetailSheet(
                    row = sensitiveRow, overridden = false,
                    onActivityChange = {}, onDataChange = {},
                    onOverrideChange = {}, onOpenSettings = {},
                )
            }
        }

        compose.onAllNodesWithText("Hibernate", substring = true).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }

    // --- v1.1 navigation: a close affordance, not only the back gesture and ---
    // --- the scrim behind this sheet (MainActivity's own doc).             ---

    @Test
    fun `tapping Close reports the callback`() {
        var closed = 0
        compose.setContent {
            JinatraTheme {
                AppDetailSheet(
                    row = sensitiveRow, overridden = false,
                    onActivityChange = {}, onDataChange = {},
                    onOverrideChange = {}, onOpenSettings = {},
                    onClose = { closed++ },
                )
            }
        }

        compose.onNodeWithText("Close").performClick()

        assertEquals(1, closed)
    }

    // F4 review finding: the old name, "still shows the app's label and
    // package alongside the close affordance", would still pass with a
    // wired-up-but-inert Close button - it only checks display. Renamed to
    // claim only what it verifies; the sibling test above already covers
    // the effect of tapping Close.
    @Test
    fun `the app's label, package and the Close label are all displayed together`() {
        compose.setContent {
            JinatraTheme {
                AppDetailSheet(
                    row = sensitiveRow, overridden = false,
                    onActivityChange = {}, onDataChange = {},
                    onOverrideChange = {}, onOpenSettings = {},
                )
            }
        }

        compose.onNodeWithText("Messages").assertIsDisplayed()
        compose.onNodeWithText("com.example.sms").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}
