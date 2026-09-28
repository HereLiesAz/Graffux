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
 */
@Suppress("FunctionNaming") // Composable naming.
@Composable
fun ProjectGateDialog(
    state: ProjectGateState,
    onLoad: () -> Unit,
    onSave: (name: String) -> Unit,
) {
    var name by remember(state.defaultName) { mutableStateOf(state.defaultName) }
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
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
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
                            onClick = { onSave(ProjectFile.sanitizeName(name)) },
                            shape = AzButtonShape.RECTANGLE,
                            modifier = Modifier.testTag(TAG_SAVE),
                        )
                    }
                }
            }
        }
    }
}

const val TAG_SCRIM = "projectGate.scrim"
const val TAG_DIALOG = "projectGate.dialog"
const val TAG_NAME = "projectGate.name"
const val TAG_LOAD = "projectGate.load"
const val TAG_SAVE = "projectGate.save"
const val TAG_BUSY = "projectGate.busy"
private const val SCRIM_ALPHA = 0.6f
