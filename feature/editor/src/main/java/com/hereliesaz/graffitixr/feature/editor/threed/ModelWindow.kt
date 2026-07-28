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
import androidx.compose.runtime.mutableIntStateOf
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

/**
 * What the 3D viewport is currently showing.
 *
 * [texture] is owned here rather than remembered inside the window because paint is work: it has to
 * outlive the window being dismissed, be autosaved like everything else, and come back when the
 * project is reopened. A composable's `remember` gives none of that.
 */
data class ModelUiState(
    val mesh: Mesh? = null,
    val name: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val texture: PaintableTexture? = null,
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
    onPaintChanged: () -> Unit = {},
    onAddToCanvas: (android.graphics.Bitmap) -> Unit = {},
    paintColor: Color = Color.White,
    brushRadiusPx: Float = 24f,
) {
    var captureRequest by remember { mutableIntStateOf(0) }
    // Whether one finger paints or orbits is a mode of this window, so it stays here. The texture
    // it paints into is not — that's the user's work, and it comes from the state.
    var paintEnabled by remember { mutableStateOf(false) }
    val texture = state.texture

    // Tracked as Compose state rather than read off PaintableTexture.version, which is a plain
    // volatile the GL thread polls — nothing recomposes when it moves, so the Clear button would
    // appear only when some unrelated change happened to redraw the window. Re-keyed on the
    // texture so opening a different project starts from that project's own paint.
    var hasPaint by remember(texture) { mutableStateOf(texture?.isPainted == true) }

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
                    onPaintChanged = { hasPaint = true; onPaintChanged() },
                    captureRequest = captureRequest,
                    onCaptured = { bitmap -> if (bitmap != null) onAddToCanvas(bitmap) },
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
                // The way a model becomes artwork: whatever angle it's turned to, as a layer.
                // A snapshot rather than a live 3D layer, because the document is a flat image —
                // turn the model again and take another if the first angle was wrong.
                AzButton(
                    text = "Add view to canvas",
                    onClick = { captureRequest++ },
                    shape = AzButtonShape.RECTANGLE,
                )
                if (hasPaint && texture != null) {
                    AzButton(
                        text = "Clear paint",
                        onClick = { texture.clear(); hasPaint = false; onPaintChanged() },
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
