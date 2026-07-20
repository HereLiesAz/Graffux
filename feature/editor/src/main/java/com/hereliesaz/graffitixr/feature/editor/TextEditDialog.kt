// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/TextEditDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import kotlin.math.roundToInt

/** Swatch palette offered for text colour. */
private val TEXT_COLORS = listOf(
    Color.White, Color.Black, Color.Red, Color(0xFFFF9800),
    Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta,
)

/**
 * Edits a text layer's content, size, colour, and bold/italic style. Every change applies live —
 * the callbacks re-rasterize the layer through the view model. Seeded from the layer's current
 * params.
 */
@Composable
fun TextEditDialog(
    initialText: String,
    initialSizeDp: Float,
    initialColorArgb: Int,
    initialBold: Boolean,
    initialItalic: Boolean,
    onTextChange: (String) -> Unit,
    onSizeChange: (Float) -> Unit,
    onColorChange: (Int) -> Unit,
    onStyleChange: (bold: Boolean, italic: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var size by remember { mutableFloatStateOf(initialSizeDp.coerceIn(8f, 300f)) }
    var colorArgb by remember { mutableIntStateOf(initialColorArgb) }
    var bold by remember { mutableStateOf(initialBold) }
    var italic by remember { mutableStateOf(initialItalic) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; onTextChange(it) },
                    label = { Text("Content") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Size ${size.roundToInt()} dp")
                Slider(
                    value = size,
                    onValueChange = { size = it; onSizeChange(it) },
                    valueRange = 8f..300f,
                )

                // Colour swatches.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TEXT_COLORS.forEach { swatch ->
                        val argb = swatch.toArgb()
                        val selected = argb == colorArgb
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(swatch, CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) Color(0xFF00E5FF) else Color.Gray,
                                    shape = CircleShape,
                                )
                                .clickable { colorArgb = argb; onColorChange(argb) },
                        )
                    }
                }

                // Bold / italic toggles.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AzButton(
                        text = if (bold) "Bold ✓" else "Bold",
                        onClick = { bold = !bold; onStyleChange(bold, italic) },
                        shape = AzButtonShape.RECTANGLE,
                    )
                    AzButton(
                        text = if (italic) "Italic ✓" else "Italic",
                        onClick = { italic = !italic; onStyleChange(bold, italic) },
                        shape = AzButtonShape.RECTANGLE,
                    )
                }
            }
        },
        confirmButton = {
            AzButton(text = "Done", onClick = onDismiss, shape = AzButtonShape.RECTANGLE)
        },
    )
}
