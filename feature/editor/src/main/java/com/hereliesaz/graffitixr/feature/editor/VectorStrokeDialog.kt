// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/VectorStrokeDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import kotlin.math.roundToInt

/**
 * Sets the outline (stroke) width for every shape on the active vector layer. 0 = no outline
 * (fill only). Seeded from [currentWidth]. No Apply button: dragging the slider only updates the
 * local preview, and [onApply] fires once with the final value when the window closes — closing
 * the dialog (however it closes) *is* applying it, rather than a separate step on top of it.
 */
@Composable
fun VectorStrokeDialog(
    currentWidth: Float,
    onApply: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var width by remember { mutableFloatStateOf(currentWidth.coerceIn(0f, 100f)) }

    FloatingWindow(title = "Stroke width", onDismiss = { onApply(width); onDismiss() }) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(if (width < 0.5f) "No outline" else "${width.roundToInt()} px")
            Slider(
                value = width,
                onValueChange = { width = it },
                valueRange = 0f..100f,
            )
        }
    }
}
