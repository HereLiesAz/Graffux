// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/TransformPanel.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.Layer
import kotlin.math.roundToInt

/**
 * Precise numeric entry for the active layer's transform — position (X/Y, px), scale (%) and
 * rotation (°). Each field commits to [actions] as soon as it parses, using the absolute setters
 * (setLayerTransform / onScaleChanged / onRotationZChanged) so typed values replace the current
 * transform rather than accumulating. Field state is keyed on the layer id, so switching layers (or
 * reopening the panel) re-seeds the fields from that layer's current values.
 */
@Composable
fun TransformPanel(
    activeLayer: Layer?,
    actions: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    if (activeLayer == null) return
    val id = activeLayer.id

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Transform", style = MaterialTheme.typography.titleSmall, color = Color.White)
            Row {
                Text("3D", style = MaterialTheme.typography.labelMedium, color = Color.White)
                Switch(
                    checked = activeLayer.is3D,
                    onCheckedChange = { actions.onToggleLayer3D(id) },
                    colors = SwitchDefaults.colors(),
                )
            }
        }

        if (activeLayer.is3D) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Rotate X°",
                    initial = remember(id) { activeLayer.rotationX.roundToInt().toString() },
                    onValue = { v -> actions.onRotationXChanged(v) },
                    allowNegative = true,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "Rotate Y°",
                    initial = remember(id) { activeLayer.rotationY.roundToInt().toString() },
                    onValue = { v -> actions.onRotationYChanged(v) },
                    allowNegative = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "X",
                initial = remember(id) { activeLayer.offset.x.roundToInt().toString() },
                onValue = { v ->
                    actions.setLayerTransform(
                        activeLayer.scale,
                        Offset(v, activeLayer.offset.y),
                        activeLayer.rotationX, activeLayer.rotationY, activeLayer.rotationZ,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Y",
                initial = remember(id) { activeLayer.offset.y.roundToInt().toString() },
                onValue = { v ->
                    actions.setLayerTransform(
                        activeLayer.scale,
                        Offset(activeLayer.offset.x, v),
                        activeLayer.rotationX, activeLayer.rotationY, activeLayer.rotationZ,
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "Scale %",
                initial = remember(id) { (activeLayer.scale * 100f).roundToInt().toString() },
                onValue = { v -> actions.onScaleChanged((v / 100f).coerceIn(0.01f, 100f)) },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Rotate °",
                initial = remember(id) { activeLayer.rotationZ.roundToInt().toString() },
                onValue = { v -> actions.onRotationZChanged(v) },
                allowNegative = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A single numeric field. Local text state (keyed on [initial] via `remember(initial)`) so the field
 * re-seeds when the active layer changes; commits [onValue] on every parseable edit.
 */
@Composable
private fun NumberField(
    label: String,
    initial: String,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filterIndexed { i, c ->
                c.isDigit() || (allowNegative && c == '-' && i == 0)
            }.take(7)
            text = filtered
            filtered.toFloatOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
