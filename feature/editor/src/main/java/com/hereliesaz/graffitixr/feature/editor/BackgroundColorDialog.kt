// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/BackgroundColorDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape

/** Canvas-background swatch palette: neutrals plus a few tints. */
private val BACKGROUND_COLORS = listOf(
    Color.Black,
    Color(0xFF1E1E1E),
    Color(0xFF808080),
    Color(0xFFD0D0D0),
    Color.White,
    Color(0xFFFDF6E3), // warm paper
    Color(0xFF0D1B2A), // navy
    Color(0xFF12261E), // forest
    Color(0xFF2A1A2E), // plum
)

/**
 * Picks the canvas/artboard background colour (the fill drawn behind every layer). [current] is
 * ringed; tapping a swatch applies it via [onSelect]. Swatch-only for now — a full colour wheel can
 * come later.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackgroundColorDialog(
    current: Color,
    onSelect: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Background") },
        text = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BACKGROUND_COLORS.forEach { swatch ->
                    val selected = swatch == current
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(swatch, RoundedCornerShape(6.dp))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) Color(0xFF00E5FF) else Color.Gray,
                                shape = RoundedCornerShape(6.dp),
                            )
                            .clickable { onSelect(swatch) },
                    )
                }
            }
        },
        confirmButton = {
            AzButton(text = "Close", onClick = onDismiss, shape = AzButtonShape.RECTANGLE)
        },
    )
}
