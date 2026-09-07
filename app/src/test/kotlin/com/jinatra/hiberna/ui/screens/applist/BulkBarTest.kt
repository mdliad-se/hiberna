// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.screens.applist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jinatra.hiberna.policy.BackgroundActivity
import com.jinatra.hiberna.preset.Preset
import com.jinatra.hiberna.ui.theme.JinatraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BulkBarTest {

    @get:Rule val compose = createComposeRule()

    private val frugal = Preset("frugal", "Frugal", BackgroundActivity.RESTRICTED, restrictBackgroundData = false)
    private val offline = Preset("offline", "Offline", BackgroundActivity.RESTRICTED, restrictBackgroundData = true)

    @Test
    fun `shows the selected count`() {
        compose.setContent {
            JinatraTheme {
                BulkBar(selectedCount = 12, presets = listOf(frugal), onApply = {}, onClear = {})
            }
        }

        compose.onNodeWithText("12 selected").assertIsDisplayed()
    }

    @Test
    fun `explains a skip count instead of staying silent about it`() {
        compose.setContent {
            JinatraTheme {
                BulkBar(
                    selectedCount = 5,
                    presets = listOf(frugal),
                    onApply = {},
                    onClear = {},
                    skippedCountFor = { 2 },
                )
            }
        }

        compose.onNodeWithText("alone", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2", substring = true).assertIsDisplayed()
    }

    @Test
    fun `says nothing about a skip count when it is zero`() {
        compose.setContent {
            JinatraTheme {
                BulkBar(selectedCount = 5, presets = listOf(frugal), onApply = {}, onClear = {})
            }
        }

        compose.onNodeWithText("alone", substring = true).assertDoesNotExist()
        compose.onNodeWithText("5 selected").assertIsDisplayed()
    }

    @Test
    fun `tapping a preset reports that preset`() {
        var applied: Preset? = null
        compose.setContent {
            JinatraTheme {
                BulkBar(
                    selectedCount = 3,
                    presets = listOf(frugal, offline),
                    onApply = { applied = it },
                    onClear = {},
                )
            }
        }

        compose.onNodeWithText("Offline").performClick()

        assertEquals(offline, applied)
    }

    @Test
    fun `tapping cancel clears the selection`() {
        var cleared = false
        compose.setContent {
            JinatraTheme {
                BulkBar(
                    selectedCount = 3,
                    presets = listOf(frugal),
                    onApply = {},
                    onClear = { cleared = true },
                )
            }
        }

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(true, cleared)
    }

    @Test
    fun `never uses Hibernate as a UI verb`() {
        compose.setContent {
            JinatraTheme {
                BulkBar(
                    selectedCount = 40,
                    presets = listOf(frugal, offline),
                    onApply = {},
                    onClear = {},
                    skippedCountFor = { 6 },
                )
            }
        }

        compose.onNodeWithText("Hibernate", substring = true).assertDoesNotExist()
        compose.onNodeWithText("40 selected").assertIsDisplayed()
        compose.onNodeWithText("Offline").assertIsDisplayed()
    }

    @Test
    fun `each preset button previews its own skip count, not a shared one`() {
        compose.setContent {
            JinatraTheme {
                BulkBar(
                    selectedCount = 10,
                    presets = listOf(frugal, offline),
                    onApply = {},
                    onClear = {},
                    skippedCountFor = { preset -> if (preset.id == "frugal") 3 else 7 },
                )
            }
        }

        compose.onNodeWithText("3 will be left alone", substring = true).assertIsDisplayed()
        compose.onNodeWithText("7 will be left alone", substring = true).assertIsDisplayed()
    }
}
