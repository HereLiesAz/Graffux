// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/CornerRadiusDialog.kt
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
 * Sets the corner radius for the active vector layer's rectangle shapes. Seeded from
 * [currentRadius]. No Apply button: dragging the slider only updates the local preview, and
 * [onApply] fires once — with the final value, clamped per-shape to half the shorter side by the
 * view model — when the window closes. Closing the dialog (however it closes) *is* applying it.
 */
@Composable
fun CornerRadiusDialog(
    currentRadius: Float,
    onApply: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var radius by remember { mutableFloatStateOf(currentRadius.coerceIn(0f, 200f)) }

    FloatingWindow(title = "Corner radius", onDismiss = { onApply(radius); onDismiss() }) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(if (radius < 0.5f) "Square corners" else "${radius.roundToInt()} px")
            Slider(
                value = radius,
                onValueChange = { radius = it },
                valueRange = 0f..200f,
            )
        }
    }
}
