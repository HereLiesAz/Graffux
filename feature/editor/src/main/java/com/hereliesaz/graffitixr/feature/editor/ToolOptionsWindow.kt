package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import kotlin.math.roundToInt

/**
 * The dials that belong to whatever tool is currently in hand.
 *
 * These were rail sliders. A slider is the wrong thing to put on a rail of round buttons — it needs
 * a length to be usable and a read-out to be meaningful, and it has no "you let go" event, which is
 * why the two that wrote to history did so once per emitted frame. A panel gives all three for free.
 *
 * Only ever shows controls that mean something right now: the magic wand's threshold is absent
 * unless Automatic is the selection mode, and the feather is absent until there is a selection to
 * soften — the same conditions the rail items carried, kept so this never becomes a window of
 * controls that do nothing.
 *
 * Brush **size** and **feathering** are deliberately not here: they live on the drag pad in the
 * Adjust palette, which shows the actual brush footprint at the actual size while you set it. Two
 * controls for one value is what this whole clean-up is undoing.
 */
@Composable
fun ToolOptionsWindow(
    stabilizerLevel: Int,
    onSetStabilizerLevel: (Int) -> Unit,
    /** Magic-wand threshold, 0..255, or null when Automatic is not the active selection mode. */
    magicWandTolerance: Int?,
    onSetMagicWandTolerance: (Int) -> Unit,
    /** Selection edge softness in px, or null when there is no selection. */
    selectionFeatherPx: Float?,
    onSetSelectionFeather: (Float) -> Unit,
    /**
     * Stamp-brush flow, or null when the built-in round brush is in hand.
     *
     * It has no meaning for the round brush, and it used to share the brush pad's horizontal axis
     * with hardness — the same drag doing two unrelated things depending on which brush was
     * selected. The pad is hardness now; flow is a parameter of the brush, so it sits here.
     */
    brushFlow: Float?,
    onSetBrushFlow: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    FloatingWindow(title = "Tool Options", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (brushFlow != null) {
                Text("Flow  ${(brushFlow * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Text(
                    "How much paint each stamp lays down.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(value = brushFlow, onValueChange = onSetBrushFlow, valueRange = 0f..1f)
            }

            Text("Stabilize  $stabilizerLevel%", style = MaterialTheme.typography.bodySmall)
            Text(
                "Smooths jitter out of a drag before it becomes a stroke.",
                style = MaterialTheme.typography.labelSmall,
            )
            Slider(
                value = stabilizerLevel.toFloat(),
                onValueChange = { onSetStabilizerLevel(it.roundToInt()) },
                valueRange = 0f..100f,
            )

            if (magicWandTolerance != null) {
                Text(
                    "Threshold  ${(magicWandTolerance / 255f * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "How far from the tapped colour still counts as the same colour.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = magicWandTolerance.toFloat(),
                    onValueChange = { onSetMagicWandTolerance(it.roundToInt()) },
                    valueRange = 0f..255f,
                )
            }

            if (selectionFeatherPx != null) {
                Text("Feather  ${selectionFeatherPx.roundToInt()} px", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Softens the boundary of the current selection. Stored on the selection, so it " +
                        "travels when the region moves.",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = selectionFeatherPx,
                    onValueChange = onSetSelectionFeather,
                    valueRange = 0f..64f,
                )
            }
        }
    }
}
