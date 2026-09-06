// SPDX-License-Identifier: GPL-3.0-or-later
package com.jinatra.hiberna.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jinatra.hiberna.ui.theme.Paper
import com.jinatra.hiberna.ui.theme.ShadowSm
import com.jinatra.hiberna.ui.theme.Teal

/**
 * The pressable surface every brutal control is built from: a hard 3px Ink
 * border, zero corner radius, and an offset-rectangle shadow that collapses
 * when pressed (see [brutalSurface]).
 *
 * The label must never say "Hibernate": Android ships its own App
 * Hibernation feature that does something different, and reusing the word
 * would misinform users about what this app does. Use "Restrict" instead.
 *
 * [isSelected] and [enabled] are deliberately independent: a control that is
 * the current choice in a group (e.g. the active option in a tri-state
 * picker) is still live and tappable, just currently the selection - a
 * screen reader must announce it as "selected", not "unavailable". Use
 * [isSelected] for that case and reserve [enabled] for controls that are
 * genuinely not actionable right now.
 */
@Composable
fun BrutalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Teal,
    contentColor: Color = Paper,
    enabled: Boolean = true,
    isSelected: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = modifier
            .semantics {
                if (!enabled) disabled()
                if (isSelected) selected = true
            }
            .brutalSurface(
                fill = fill,
                // A disabled control loses its shadow entirely: it cannot be
                // pushed because nothing happens.
                shadow = if (enabled) ShadowSm else 0.dp,
                pressed = enabled && pressed,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = contentColor)
    }
}
