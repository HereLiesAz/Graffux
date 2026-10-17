// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/ProjectGateDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.model.ProjectFile

/**
 * The mandatory project dialog: shown whenever there is no project to work in (first launch with
 * no projects, and File > New). There is nothing to do without a project, so it has no cancel path:
 * no close button, taps outside are swallowed, and it installs no back handler, so Back does what
 * it does at the app's root today (the system default: leave the app).
 *
 * Load opens the system file picker; backing out of the picker returns here. Save creates the
 * project under the typed name. GPU calibration runs in the background while this is open
 * (see gpu/CalibrationCoordinator); the busy state covers its capped tail after the tap.
 *
 * It also sets the project's canvas size (the screen's by default, a preset, or typed) and offers
 * to import images up front: [onPickImages] opens the host's picker and [pickedImageCount] reports
 * what it returned, which the host passes to the ViewModel along with the size on Save.
 */
@Suppress("FunctionNaming", "LongMethod") // Composable naming; one card of stacked sections.
@Composable
fun ProjectGateDialog(
    state: ProjectGateState,
    onLoad: () -> Unit,
    onSave: (name: String, width: Int, height: Int) -> Unit,
    pickedImageCount: Int = 0,
    onPickImages: () -> Unit = {},
) {
    var name by remember(state.defaultName) { mutableStateOf(state.defaultName) }
    var sizeChoice by remember(state.defaultName) { mutableStateOf<DocumentPreset?>(null) }
    var customWidth by remember(state.defaultWidth) { mutableStateOf(state.defaultWidth.toString()) }
    var customHeight by remember(state.defaultHeight) { mutableStateOf(state.defaultHeight.toString()) }
    // null = "Screen" (the size projects have always opened at); a preset's isCustom = typed sizes.
    val choice = sizeChoice
    val width = when {
        choice == null -> state.defaultWidth
        choice.isCustom -> customWidth.toIntOrNull()?.coerceAtLeast(1) ?: state.defaultWidth
        else -> choice.width
    }
    val height = when {
        choice == null -> state.defaultHeight
        choice.isCustom -> customHeight.toIntOrNull()?.coerceAtLeast(1) ?: state.defaultHeight
        else -> choice.height
    }
    val busy = state.busyLabel != null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            // Swallow every touch outside the card: tapping the scrim never dismisses.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
            .testTag(TAG_SCRIM),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.padding(24.dp).widthIn(max = 420.dp).testTag(TAG_DIALOG),
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Project", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_NAME),
                )
                if (!busy) {
                    CanvasSizeSection(
                        state = state,
                        choice = sizeChoice,
                        onChoice = { sizeChoice = it },
                        customWidth = customWidth,
                        onCustomWidth = { customWidth = it },
                        customHeight = customHeight,
                        onCustomHeight = { customHeight = it },
                    )
                    AzButton(
                        text = if (pickedImageCount > 0) "Images: $pickedImageCount — change…" else "Import images…",
                        onClick = onPickImages,
                        shape = AzButtonShape.RECTANGLE,
                        modifier = Modifier.testTag(TAG_IMAGES),
                    )
                }
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(state.busyLabel.orEmpty(), modifier = Modifier.testTag(TAG_BUSY))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AzButton(
                            text = "Load…",
                            onClick = onLoad,
                            shape = AzButtonShape.RECTANGLE,
                            modifier = Modifier.testTag(TAG_LOAD),
                        )
                        AzButton(
                            text = "Save",
                            onClick = { onSave(ProjectFile.sanitizeName(name), width, height) },
                            shape = AzButtonShape.RECTANGLE,
                            modifier = Modifier.testTag(TAG_SAVE),
                        )
                    }
                }
            }
        }
    }
}

/** The canvas-size choices: Screen (null), the document presets, and Custom with typed fields. */
@Suppress("FunctionNaming", "LongParameterList") // Composable naming; hoisted state for the dialog.
@Composable
private fun CanvasSizeSection(
    state: ProjectGateState,
    choice: DocumentPreset?,
    onChoice: (DocumentPreset?) -> Unit,
    customWidth: String,
    onCustomWidth: (String) -> Unit,
    customHeight: String,
    onCustomHeight: (String) -> Unit,
) {
    Text("Canvas size", style = MaterialTheme.typography.titleSmall)
    (listOf<DocumentPreset?>(null) + DOCUMENT_PRESETS).forEach { option ->
        val label = when {
            option == null -> "Screen  (${state.defaultWidth}×${state.defaultHeight})"
            option.isCustom -> "Custom…"
            else -> "${option.label}  (${option.width}×${option.height})"
        }
        AzButton(
            text = if (option == choice) "$label ✓" else label,
            onClick = { onChoice(option) },
            shape = AzButtonShape.RECTANGLE,
            modifier = Modifier.testTag("$TAG_SIZE_PREFIX${option?.label ?: "Screen"}"),
        )
    }
    if (choice?.isCustom == true) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SizeField("Width", customWidth, onCustomWidth, TAG_CUSTOM_WIDTH, Modifier.weight(1f))
            SizeField("Height", customHeight, onCustomHeight, TAG_CUSTOM_HEIGHT, Modifier.weight(1f))
        }
    }
}

@Suppress("FunctionNaming") // Composable naming.
@Composable
private fun SizeField(label: String, value: String, onValue: (String) -> Unit, tag: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.filter(Char::isDigit).take(MAX_DIGITS)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.testTag(tag),
    )
}

const val TAG_SCRIM = "projectGate.scrim"
const val TAG_DIALOG = "projectGate.dialog"
const val TAG_NAME = "projectGate.name"
const val TAG_LOAD = "projectGate.load"
const val TAG_SAVE = "projectGate.save"
const val TAG_BUSY = "projectGate.busy"
const val TAG_IMAGES = "projectGate.images"
const val TAG_SIZE_PREFIX = "projectGate.size."
const val TAG_CUSTOM_WIDTH = "projectGate.customWidth"
const val TAG_CUSTOM_HEIGHT = "projectGate.customHeight"
private const val MAX_DIGITS = 5
private const val SCRIM_ALPHA = 0.6f
