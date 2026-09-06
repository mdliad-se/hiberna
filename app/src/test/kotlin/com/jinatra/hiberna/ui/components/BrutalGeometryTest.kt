// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-geometry tests for the neubrutalist shadow, decoupled from Compose's
 * drawing pipeline so they run as plain JVM tests (no Robolectric needed):
 * [Dp] is a value class with no Android runtime dependency.
 *
 * Two Global Constraints under test:
 *  - "On press the element translates by exactly its shadow offset and the
 *    shadow collapses to zero, so it reads as pushed into the page."
 *  - "A disabled control loses its shadow entirely - it cannot be pushed
 *    because nothing happens."
 */
class BrutalGeometryTest {

    @Test
    fun `unpressed surface sits flat with its full shadow depth`() {
        val geometry = brutalGeometry(shadow = 6.dp, pressed = false)

        assertEquals(0.dp, geometry.offset)
        assertEquals(6.dp, geometry.shadowDepth)
    }

    @Test
    fun `pressed surface translates by exactly the shadow offset and collapses it`() {
        val geometry = brutalGeometry(shadow = 6.dp, pressed = true)

        assertEquals(6.dp, geometry.offset)
        assertEquals(0.dp, geometry.shadowDepth)
    }

    @Test
    fun `a control given zero shadow has nothing to collapse when pressed`() {
        // This is the shape a disabled BrutalButton passes: shadow = 0.dp.
        // Nothing happens on press because there is no shadow to give up.
        val unpressed = brutalGeometry(shadow = 0.dp, pressed = false)
        val pressed = brutalGeometry(shadow = 0.dp, pressed = true)

        assertEquals(0.dp, unpressed.offset)
        assertEquals(0.dp, unpressed.shadowDepth)
        assertEquals(0.dp, pressed.offset)
        assertEquals(0.dp, pressed.shadowDepth)
    }
}
