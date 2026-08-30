// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/ShapeSizeDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * Sets the width × height (px) of the active vector layer's shapes — the numeric alternative to
 * dragging resize handles (which needs a device). Fields are seeded from the layer's first shape;
 * [isLine] hides the height field, since a line's height is ignored (its width is its length). No
 * Apply button: typing only updates the local fields, and [onConfirm] fires once — with whatever
 * last parsed, falling back to the seeded value for a field left empty or mid-edit — when the
 * window closes. Closing the dialog (however it closes) *is* applying it.
 */
@Composable
fun ShapeSizeDialog(
    currentWidth: Float,
    currentHeight: Float,
    isLine: Boolean,
    onConfirm: (width: Float, height: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var widthText by remember { mutableStateOf(currentWidth.toInt().toString()) }
    var heightText by remember { mutableStateOf(currentHeight.toInt().toString()) }

    fun confirmAndDismiss() {
        val w = widthText.toFloatOrNull() ?: currentWidth
        val h = if (isLine) currentHeight else (heightText.toFloatOrNull() ?: currentHeight)
        onConfirm(w, h)
        onDismiss()
    }

    FloatingWindow(title = if (isLine) "Line length" else "Shape size", onDismiss = ::confirmAndDismiss) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { widthText = it.filter(Char::isDigit).take(4) },
                    label = { Text(if (isLine) "Length" else "Width") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                if (!isLine) {
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = { heightText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Height") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
