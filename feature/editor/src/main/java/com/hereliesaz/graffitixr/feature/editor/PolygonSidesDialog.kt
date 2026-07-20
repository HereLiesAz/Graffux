// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/PolygonSidesDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import kotlin.math.roundToInt

/**
 * Sets the vertex count of the active vector layer's polygon shapes (3–12). Seeded from
 * [currentSides]; [onApply] pushes the value to the view model, which floors it at 3.
 */
@Composable
fun PolygonSidesDialog(
    currentSides: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var sides by remember { mutableIntStateOf(currentSides.coerceIn(3, 12)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Polygon sides") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("$sides sides")
                Slider(
                    value = sides.toFloat(),
                    onValueChange = { sides = it.roundToInt() },
                    valueRange = 3f..12f,
                    steps = 8, // 3..12 inclusive → 10 stops → 8 intermediate steps
                )
            }
        },
        confirmButton = {
            AzButton(text = "Apply", onClick = { onApply(sides) }, shape = AzButtonShape.RECTANGLE)
        },
        dismissButton = {
            AzButton(text = "Cancel", onClick = onDismiss, shape = AzButtonShape.RECTANGLE)
        },
    )
}
