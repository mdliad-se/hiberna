// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

/**
 * Runs under Robolectric so it stays part of `:app:testDebugUnitTest` with no
 * device needed - the brief's `connectedDebugAndroidTest` spec predates
 * GateScreenTest/BulkBarTest moving this pattern to `src/test`; see those
 * files' docs for why.
 */
@RunWith(RobolectricTestRunner::class)
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
}
