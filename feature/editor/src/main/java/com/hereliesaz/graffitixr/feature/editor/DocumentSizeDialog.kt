// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/DocumentSizeDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape

/**
 * Picks the artboard / document size. Tapping a preset applies it immediately; "Custom" reveals
 * width/height fields (seeded from the current document) applied via the Apply button.
 */
@Composable
fun DocumentSizeDialog(
    currentWidth: Int,
    currentHeight: Int,
    onConfirm: (width: Int, height: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var showCustom by remember { mutableStateOf(false) }
    var widthText by remember { mutableStateOf(currentWidth.toString()) }
    var heightText by remember { mutableStateOf(currentHeight.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Document size") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DOCUMENT_PRESETS.forEach { preset ->
                    if (preset.isCustom) {
                        AzButton(
                            text = "Custom…",
                            onClick = { showCustom = true },
                            shape = AzButtonShape.RECTANGLE,
                        )
                    } else {
                        AzButton(
                            text = "${preset.label}  (${preset.width}×${preset.height})",
                            onClick = { onConfirm(preset.width, preset.height) },
                            shape = AzButtonShape.RECTANGLE,
                        )
                    }
                }

                if (showCustom) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = widthText,
                            onValueChange = { widthText = it.filter(Char::isDigit).take(5) },
                            label = { Text("Width") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = heightText,
                            onValueChange = { heightText = it.filter(Char::isDigit).take(5) },
                            label = { Text("Height") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (showCustom) {
                AzButton(
                    text = "Apply",
                    onClick = {
                        val w = widthText.toIntOrNull() ?: currentWidth
                        val h = heightText.toIntOrNull() ?: currentHeight
                        onConfirm(w, h)
                    },
                    shape = AzButtonShape.RECTANGLE,
                )
            }
        },
        dismissButton = {
            AzButton(text = "Cancel", onClick = onDismiss, shape = AzButtonShape.RECTANGLE)
        },
    )
}
