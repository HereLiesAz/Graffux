// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/VectorStrokeDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import kotlin.math.roundToInt

/**
 * Sets the outline (stroke) width for every shape on the active vector layer. 0 = no outline
 * (fill only). Seeded from [currentWidth]; [onApply] pushes the chosen width to the view model.
 * Stroke colour is handled separately (the colour picker recolours the active layer), so this
 * dialog is width-only — the one vector styling control the colour picker doesn't cover.
 */
@Composable
fun VectorStrokeDialog(
    currentWidth: Float,
    onApply: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var width by remember { mutableFloatStateOf(currentWidth.coerceIn(0f, 100f)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stroke width") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(if (width < 0.5f) "No outline" else "${width.roundToInt()} px")
                Slider(
                    value = width,
                    onValueChange = { width = it },
                    valueRange = 0f..100f,
                )
            }
        },
        confirmButton = {
            AzButton(
                text = "Apply",
                onClick = { onApply(width) },
                shape = AzButtonShape.RECTANGLE,
            )
        },
        dismissButton = {
            AzButton(text = "Cancel", onClick = onDismiss, shape = AzButtonShape.RECTANGLE)
        },
    )
}
