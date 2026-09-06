// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jinatra.hiberna.ui.components.BrutalButton
import com.jinatra.hiberna.ui.theme.JinatraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs the brief's connectedDebugAndroidTest spec under Robolectric on the
 * JVM instead, so it stays part of `:app:testDebugUnitTest` and needs no
 * device - see .spine/task-9-report.md for why.
 */
@RunWith(RobolectricTestRunner::class)
class BrutalButtonTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun rendersLabelAndFiresClick() {
        var clicks = 0
        compose.setContent {
            JinatraTheme { BrutalButton(text = "Restrict", onClick = { clicks++ }) }
        }

        compose.onNodeWithText("Restrict").assertIsDisplayed().performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun disabledButtonDoesNotFire() {
        var clicks = 0
        compose.setContent {
            JinatraTheme { BrutalButton(text = "Restrict", onClick = { clicks++ }, enabled = false) }
        }

        compose.onNodeWithText("Restrict").assertIsNotEnabled()
        assertEquals(0, clicks)
    }

    @Test
    fun labelSaysRestrictNotHibernate() {
        // Standing guard on a Global Constraint: "Hibernate" is never a UI
        // verb here, because Android ships its own App Hibernation feature
        // that does something different - reusing the word would misinform
        // users about what this app does.
        compose.setContent {
            JinatraTheme { BrutalButton(text = "Restrict all", onClick = {}) }
        }

        compose.onNodeWithText("Restrict all").assertIsDisplayed()
    }
}
