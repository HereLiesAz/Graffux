// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/DocumentSizeDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape
import com.hereliesaz.graffitixr.common.azphalt.SubstratePreset
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/**
 * Picks the artboard / document size. Tapping a preset applies it and closes the dialog in one
 * step — it's a complete choice, nothing to preview first. "Custom" reveals width/height fields
 * instead (seeded from the current document); no Apply button there either — typing only updates
 * the local fields, and the values are pushed once, when the window closes. Closing the dialog
 * (however it closes) *is* applying it.
 *
 * Below the sizes sits the project's canvas surface ([SubstratePreset]): [currentSubstrateId] is
 * ticked, and tapping another applies it through [onSubstrate] at once, leaving the window open.
 * The surface only shows in brushes whose Substrate response is above zero.
 */
@Suppress("FunctionNaming", "LongParameterList") // Composable naming; the two surface params are optional.
@Composable
fun DocumentSizeDialog(
    currentWidth: Int,
    currentHeight: Int,
    onConfirm: (width: Int, height: Int) -> Unit,
    onDismiss: () -> Unit,
    currentSubstrateId: String = SubstratePreset.DEFAULT.id,
    onSubstrate: (substrateId: String) -> Unit = {},
) {
    var showCustom by remember { mutableStateOf(false) }
    var widthText by remember { mutableStateOf(currentWidth.toString()) }
    var heightText by remember { mutableStateOf(currentHeight.toString()) }

    fun confirmAndDismiss() {
        if (showCustom) {
            val w = widthText.toIntOrNull() ?: currentWidth
            val h = heightText.toIntOrNull() ?: currentHeight
            onConfirm(w, h)
        }
        onDismiss()
    }

    FloatingWindow(title = "Document size", onDismiss = ::confirmAndDismiss) {
        // FloatingWindow already scrolls its content; a second vertical scroller receives
        // unbounded height from the outer one and crashes during measurement.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DOCUMENT_PRESETS.forEach { preset ->
                if (preset.isCustom) {
                    GraffuxButton(
                        text = "Custom…",
                        onClick = { showCustom = true },
                        shape = GraffuxButtonShape.RECTANGLE,
                    )
                } else {
                    GraffuxButton(
                        text = "${preset.label}  (${preset.width}×${preset.height})",
                        onClick = { onConfirm(preset.width, preset.height); onDismiss() },
                        shape = GraffuxButtonShape.RECTANGLE,
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

            Text("Surface", modifier = Modifier.padding(top = 12.dp))
            val currentSubstrate = SubstratePreset.fromId(currentSubstrateId)
            SubstratePreset.entries.forEach { preset ->
                GraffuxButton(
                    text = if (preset == currentSubstrate) "${preset.displayName} ✓" else preset.displayName,
                    onClick = { onSubstrate(preset.id) },
                    shape = GraffuxButtonShape.RECTANGLE,
                )
            }
        }
    }
}
