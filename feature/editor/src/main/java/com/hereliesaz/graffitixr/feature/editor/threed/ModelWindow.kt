// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/threed/ModelWindow.kt
package com.hereliesaz.graffitixr.feature.editor.threed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.mesh.Mesh
import com.hereliesaz.graffitixr.design.components.FloatingWindow

/** What the 3D viewport is currently showing. */
data class ModelUiState(
    val mesh: Mesh? = null,
    val name: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * The 3D model viewport, in the same floating window every other tool uses — so a model sits over
 * the canvas alongside the reference image and the layer rail rather than taking the user to a
 * separate mode.
 *
 * Paint mode swaps what one finger does — orbit becomes brush — while two fingers keep navigating,
 * so getting to the far side of a model doesn't mean putting the brush down first. Paint lands on
 * the model's texture at the UV under the touch, so it stays where it was put however the model is
 * afterwards turned.
 */
@Composable
fun ModelWindow(
    state: ModelUiState,
    onPickModel: () -> Unit,
    onDismiss: () -> Unit,
    paintColor: Color = Color.White,
    brushRadiusPx: Float = 24f,
) {
    // Paint mode and the texture live here rather than in EditorUiState: a model is a tool window,
    // not a layer, and its paint has no meaning once the window is gone.
    var paintEnabled by remember { mutableStateOf(false) }
    val texture = remember(state.mesh) {
        state.mesh?.let { PaintableTexture(PaintableTexture.DEFAULT_SIZE, PaintableTexture.DEFAULT_SIZE) }
    }

    FloatingWindow(title = state.name ?: "3D Model", onDismiss = onDismiss) {
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            state.error != null -> Text(
                text = state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            state.mesh != null -> {
                ModelView(
                    mesh = state.mesh,
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    texture = texture,
                    paintEnabled = paintEnabled,
                    paintColor = paintColor,
                    brushRadiusPx = brushRadiusPx,
                )
                Text(
                    text = if (paintEnabled) {
                        "${state.mesh.triangleCount} triangles · drag to paint, two fingers to move"
                    } else {
                        "${state.mesh.triangleCount} triangles · drag to orbit, pinch to zoom"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                AzButton(
                    text = if (paintEnabled) "Painting — tap to orbit" else "Paint on model",
                    onClick = { paintEnabled = !paintEnabled },
                    shape = AzButtonShape.RECTANGLE,
                )
                if (texture?.isPainted == true) {
                    AzButton(
                        text = "Clear paint",
                        onClick = { texture.clear() },
                        shape = AzButtonShape.RECTANGLE,
                    )
                }
            }
            else -> Text(
                "Load an .obj model to inspect it.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        AzButton(
            text = if (state.mesh != null) "Load Another" else "Load Model",
            onClick = onPickModel,
            shape = AzButtonShape.RECTANGLE,
        )
    }
}
