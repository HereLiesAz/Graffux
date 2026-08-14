// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/BrushStudioWindow.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.design.components.ConfirmDialog
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Procreate's Brush Studio: the parameters of the active stamp brush, live. Every edit is applied to
 * the live brush immediately (see EditorViewModel.onEditBrushDraft), so the preview stroke and the
 * next real stroke show the same thing.
 *
 * Deliberately limited to the knobs the stamp renderer actually honours today — spacing, opacity,
 * hardness, the two jitters, and scatter. `angle`/`followStroke` only mean something for an image
 * tip (a custom brush has none) and `grainPath` is parsed but unused by the renderer, so exposing
 * any of them here would be three dead dials on an editor whose whole job is immediate feedback.
 */
@Composable
fun BrushStudioWindow(
    draft: AzphaltBrush,
    brushColor: Color,
    isSaved: Boolean,
    onEdit: ((AzphaltBrush) -> AzphaltBrush) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    if (confirmingDelete) {
        ConfirmDialog(
            title = "Delete brush?",
            message = "\"${draft.name}\" will be deleted permanently. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { confirmingDelete = false; onDelete(); onDismiss() },
            onDismiss = { confirmingDelete = false },
        )
    }

    FloatingWindow(title = "Brush Studio", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { name -> onEdit { it.copy(name = name) } },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            BrushPreview(draft, brushColor)

            ParamSlider("Spacing", draft.spacing, 0.01f..2f, asFraction = true) { v -> onEdit { it.copy(spacing = v) } }
            ParamSlider("Opacity", draft.opacity, 0f..1f) { v -> onEdit { it.copy(opacity = v) } }
            ParamSlider("Hardness", draft.hardness, 0f..1f) { v -> onEdit { it.copy(hardness = v) } }
            ParamSlider("Size jitter", draft.sizeJitter, 0f..1f) { v -> onEdit { it.copy(sizeJitter = v) } }
            ParamSlider("Opacity jitter", draft.opacityJitter, 0f..1f) { v -> onEdit { it.copy(opacityJitter = v) } }
            ParamSlider("Scatter", draft.scatter, 0f..2f, asFraction = true) { v -> onEdit { it.copy(scatter = v) } }

            AzButton(text = "Save Brush", onClick = onSave, shape = AzButtonShape.RECTANGLE)
            if (isSaved) {
                AzButton(text = "Delete Brush", onClick = { confirmingDelete = true }, shape = AzButtonShape.RECTANGLE)
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    // Spacing and scatter are multiples of the tip diameter, not percentages of a whole — "0.25×"
    // reads as what it is, where "25%" would suggest a ceiling of 1 that neither actually has.
    asFraction: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val shown = if (asFraction) "${(value * 100).roundToInt() / 100f}×" else "${(value * 100).roundToInt()}%"
    Text("$label  $shown", style = MaterialTheme.typography.bodySmall)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

/**
 * A test stroke rendered with the draft's own dab placement — the same [BrushStamps.dabs] the real
 * engine uses, so spacing/jitter/scatter preview honestly rather than being approximated. The dabs
 * are drawn as plain circles here (no radial-gradient tip), so hardness is the one parameter the
 * preview only hints at; it reads correctly on the canvas itself.
 */
@Composable
private fun BrushPreview(brush: AzphaltBrush, color: Color) {
    Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
        val diameter = size.height / 3f
        // Interleaved [x0,y0,x1,y1,…] — BrushStamps' own convention.
        val path = ArrayList<Float>(SAMPLES * 2)
        for (i in 0 until SAMPLES) {
            val t = i / (SAMPLES - 1f)
            path.add(diameter + t * (size.width - diameter * 2f))
            path.add(size.height / 2f + sin(t * 2f * PI_F) * size.height / 5f)
        }
        BrushStamps.dabs(path, diameter, brush, seed = PREVIEW_SEED).forEach { dab ->
            drawCircle(
                color = color.copy(alpha = color.alpha * dab.alpha),
                radius = dab.radius,
                center = Offset(dab.x, dab.y),
            )
        }
    }
}

/** Fixed so the preview doesn't reshuffle its jitter on every recomposition (i.e. every slider tick). */
private const val PREVIEW_SEED = 12345L
private const val SAMPLES = 48
private const val PI_F = 3.1415927f
