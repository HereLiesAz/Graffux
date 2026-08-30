// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/PolygonSidesDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import kotlin.math.roundToInt

/**
 * Sets the vertex count of the active vector layer's polygon shapes (3–12). Seeded from
 * [currentSides]. No Apply button: dragging the slider only updates the local preview, and
 * [onApply] fires once — with the final value, floored at 3 by the view model — when the window
 * closes. Closing the dialog (however it closes) *is* applying it.
 */
@Composable
fun PolygonSidesDialog(
    currentSides: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var sides by remember { mutableIntStateOf(currentSides.coerceIn(3, 12)) }

    FloatingWindow(title = "Polygon sides", onDismiss = { onApply(sides); onDismiss() }) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("$sides sides")
            Slider(
                value = sides.toFloat(),
                onValueChange = { sides = it.roundToInt() },
                valueRange = 3f..12f,
                steps = 8, // 3..12 inclusive → 10 stops → 8 intermediate steps
            )
        }
    }
}
