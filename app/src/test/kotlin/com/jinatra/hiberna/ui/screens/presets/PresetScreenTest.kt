// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.presets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.preset.DEFAULT_PRESETS
import com.jinatra.hiberna.preset.Preset
import com.jinatra.hiberna.ui.theme.JinatraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs under Robolectric so it stays part of `:app:testDebugUnitTest` - same
 * pattern as BulkBarTest/AppListScreenTest.
 */
@RunWith(RobolectricTestRunner::class)
class PresetScreenTest {

    @get:Rule val compose = createComposeRule()

    private val balanced =
        Preset("balanced", "Balanced", BackgroundActivity.OPTIMIZED, restrictBackgroundData = false)
    private val frugal =
        Preset("frugal", "Frugal", BackgroundActivity.RESTRICTED, restrictBackgroundData = false)

    @Test
    fun `lists every preset's name and a summary of what it sets`() {
        compose.setContent {
            JinatraTheme {
                PresetScreen(presets = listOf(balanced, frugal), onSave = {}, onDelete = {})
            }
        }

        compose.onNodeWithText("Balanced").assertIsDisplayed()
        compose.onNodeWithText("Frugal").assertIsDisplayed()
        compose.onNodeWithText("optimized", substring = true).assertIsDisplayed()
        compose.onNodeWithText("restricted", substring = true).assertIsDisplayed()
    }

    @Test
    fun `toggling skip-sensitive reports the preset with that field flipped`() {
        var saved: Preset? = null
        compose.setContent {
            JinatraTheme {
                PresetScreen(presets = listOf(balanced), onSave = { saved = it }, onDelete = {})
            }
        }

        compose.onNodeWithText("Skip apps that may break").performClick()

        assertEquals(balanced.copy(skipSensitive = !balanced.skipSensitive), saved)
    }

    // --- judgement call (b): delete is destructive with no undo, so it is a ---
    // --- two-tap arm/confirm rather than a first-tap delete or a modal.    ---

    @Test
    fun `a single tap on delete does not delete - it only arms confirmation`() {
        var deleted: String? = null
        compose.setContent {
            JinatraTheme {
                PresetScreen(presets = listOf(balanced), onSave = {}, onDelete = { deleted = it })
            }
        }

        compose.onNodeWithText("Delete").performClick()

        assertNull(deleted)
    }

    @Test
    fun `a second tap after arming actually deletes`() {
        var deleted: String? = null
        compose.setContent {
            JinatraTheme {
                PresetScreen(presets = listOf(balanced), onSave = {}, onDelete = { deleted = it })
            }
        }

        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Tap again to delete").performClick()

        assertEquals("balanced", deleted)
    }

    @Test
    fun `arming one preset's delete never arms a second at the same time`() {
        // Brand v1.1 caps Signal (the armed-confirm fill) at one highlight
        // per screen - a second row must not also read as armed just because
        // the first one is. Asserted by node count, not colour: this test
        // stays a semantic-tree check, not a pixel one.
        compose.setContent {
            JinatraTheme {
                PresetScreen(presets = listOf(balanced, frugal), onSave = {}, onDelete = {})
            }
        }

        compose.onAllNodesWithText("Delete")[0].performClick()

        assertEquals(1, compose.onAllNodesWithText("Tap again to delete").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("Delete").fetchSemanticsNodes().size)
    }

    // --- F1: an empty list must not be a dead end - it offers a way back. ---

    @Test
    fun `offers no restore action while any preset still exists`() {
        compose.setContent {
            JinatraTheme {
                PresetScreen(presets = listOf(balanced), onSave = {}, onDelete = {})
            }
        }

        compose.onNodeWithText("Restore default presets").assertDoesNotExist()
    }

    @Test
    fun `an empty preset list offers to restore the defaults`() {
        compose.setContent {
            JinatraTheme {
                PresetScreen(presets = emptyList(), onSave = {}, onDelete = {})
            }
        }

        compose.onNodeWithText("Restore default presets").assertIsDisplayed()
    }

    @Test
    fun `tapping restore reports every default preset to onSave`() {
        val saved = mutableListOf<Preset>()
        compose.setContent {
            JinatraTheme {
                PresetScreen(presets = emptyList(), onSave = { saved += it }, onDelete = {})
            }
        }

        compose.onNodeWithText("Restore default presets").performClick()

        assertEquals(DEFAULT_PRESETS, saved)
    }

    @Test
    fun `never uses Hibernate as a UI verb`() {
        compose.setContent {
            JinatraTheme {
                PresetScreen(presets = listOf(balanced, frugal), onSave = {}, onDelete = {})
            }
        }

        compose.onAllNodesWithText("Hibernate", substring = true).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
    }

    // --- v1.1 navigation: the back affordance Task 2 adds - previously the ---
    // --- only way back from this screen was the invisible system gesture. ---

    @Test
    fun `tapping Back reports the callback`() {
        var backTapped = false
        compose.setContent {
            JinatraTheme {
                PresetScreen(
                    presets = listOf(balanced, frugal),
                    onSave = {},
                    onDelete = {},
                    onBack = { backTapped = true },
                )
            }
        }

        compose.onNodeWithText("Back").performClick()

        assertEquals(true, backTapped)
    }

    @Test
    fun `shows a title alongside the back affordance`() {
        compose.setContent {
            JinatraTheme {
                PresetScreen(presets = listOf(balanced, frugal), onSave = {}, onDelete = {}, onBack = {})
            }
        }

        compose.onNodeWithText("Presets").assertIsDisplayed()
        compose.onNodeWithText("Back").assertIsDisplayed()
    }
}
