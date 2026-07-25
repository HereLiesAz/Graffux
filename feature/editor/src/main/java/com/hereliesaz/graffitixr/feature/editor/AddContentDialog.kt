// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/AddContentDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape

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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(choices) { (label, onSelect) ->
                    AzButton(
                        text = label,
                        onClick = { onSelect(); onDismiss() },
                        shape = AzButtonShape.RECTANGLE,
                    )
                }
            }
        },
        confirmButton = {
            AzButton(text = "Cancel", onClick = onDismiss, shape = AzButtonShape.RECTANGLE)
        },
    )
}
