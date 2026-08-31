// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/AddContentDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.design.components.AzFullWidthButtonHeight
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * Window listing everything that can be added as a new layer — text and the basic shapes.
 * Replaces the flat "Add" rail sub-item list (was 7 always-visible rail buttons); the rail now
 * has a single "Add" item that opens this instead.
 */
@Composable
fun AddContentDialog(
    onAddText: () -> Unit,
    onAddRectangle: () -> Unit,
    onAddEllipse: () -> Unit,
    onAddLine: () -> Unit,
    onAddTriangle: () -> Unit,
    onAddPentagon: () -> Unit,
    onAddHexagon: () -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = listOf(
        "Text" to onAddText,
        "Rectangle" to onAddRectangle,
        "Ellipse" to onAddEllipse,
        "Line" to onAddLine,
        "Triangle" to onAddTriangle,
        "Pentagon" to onAddPentagon,
        "Hexagon" to onAddHexagon,
    )
    FloatingWindow(title = "Add", onDismiss = onDismiss) {
        // Plain Column of Rows, not LazyVerticalGrid: this window's content already sits inside
        // FloatingWindow's own scrollable Column, and a lazy grid nested there is built on
        // SubcomposeLayout -- AzWindow's sizing asks its content for an intrinsic measurement,
        // which Compose refuses to do across a SubcomposeLayout boundary, crashing the instant
        // this window opened. Seven fixed choices have nothing to virtualize anyway.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { (label, onSelect) ->
                        AzButton(
                            text = label,
                            onClick = { onSelect(); onDismiss() },
                            shape = AzButtonShape.RECTANGLE,
                            modifier = Modifier.weight(1f).height(AzFullWidthButtonHeight),
                        )
                    }
                    repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}
