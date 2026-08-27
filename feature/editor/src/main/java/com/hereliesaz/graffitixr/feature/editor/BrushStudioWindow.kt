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
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushSampleBuilder
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.design.components.ConfirmDialog
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Phone-first Brush Studio.
 *
 * The engine underneath can now expose Krita-class sensor routing, but the surface deliberately does
 * not become Krita's desktop/tablet cockpit. Baseline brush controls stay immediately visible; dynamic
 * routing is collapsed behind one button and starts with useful one-tap mappings. The brush model is
 * already general enough for a later advanced curve/matrix editor without forcing that complexity on
 * somebody who just wants to draw on a phone.
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
    var showDynamics by remember { mutableStateOf(false) }
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

            AzButton(
                text = if (showDynamics) "Dynamics ▴" else "Dynamics ▾",
                onClick = { showDynamics = !showDynamics },
                shape = AzButtonShape.RECTANGLE,
            )
            if (showDynamics) {
                Text(
                    "Input mappings (${draft.dynamics.size})",
                    style = MaterialTheme.typography.bodySmall,
                )
                DynamicsPreset(
                    label = "Pressure → Size",
                    active = draft.hasRoute(BrushSensor.PRESSURE, BrushParameter.SIZE),
                ) {
                    onEdit {
                        it.toggleRoute(
                            BrushSensorBinding(
                                sensor = BrushSensor.PRESSURE,
                                parameter = BrushParameter.SIZE,
                                outputMin = 0.2f,
                                outputMax = 1f,
                            )
                        )
                    }
                }
                DynamicsPreset(
                    label = "Pressure → Opacity",
                    active = draft.hasRoute(BrushSensor.PRESSURE, BrushParameter.OPACITY),
                ) {
                    onEdit {
                        it.toggleRoute(
                            BrushSensorBinding(
                                sensor = BrushSensor.PRESSURE,
                                parameter = BrushParameter.OPACITY,
                                outputMin = 0.1f,
                                outputMax = 1f,
                            )
                        )
                    }
                }
                DynamicsPreset(
                    label = "Speed → Thin",
                    active = draft.hasRoute(BrushSensor.SPEED, BrushParameter.SIZE),
                ) {
                    onEdit {
                        it.toggleRoute(
                            BrushSensorBinding(
                                sensor = BrushSensor.SPEED,
                                parameter = BrushParameter.SIZE,
                                inputMin = 0f,
                                inputMax = 2f,
                                outputMin = 1f,
                                outputMax = 0.45f,
                            )
                        )
                    }
                }
                DynamicsPreset(
                    label = "Tilt → Rotation",
                    active = draft.hasRoute(BrushSensor.TILT, BrushParameter.ROTATION),
                ) {
                    onEdit {
                        it.toggleRoute(
                            BrushSensorBinding(
                                sensor = BrushSensor.TILT,
                                parameter = BrushParameter.ROTATION,
                                inputMin = 0f,
                                inputMax = HALF_PI_F,
                                outputMin = 0f,
                                outputMax = 180f,
                            )
                        )
                    }
                }
                Text(
                    "Advanced curves and arbitrary sensor routes stay one level deeper; these presets keep the phone surface fast.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            AzButton(text = "Save Brush", onClick = onSave, shape = AzButtonShape.RECTANGLE)
            if (isSaved) {
                AzButton(text = "Delete Brush", onClick = { confirmingDelete = true }, shape = AzButtonShape.RECTANGLE)
            }
        }
    }
}

@Composable
private fun DynamicsPreset(label: String, active: Boolean, onClick: () -> Unit) {
    AzButton(
        text = if (active) "✓ $label" else label,
        onClick = onClick,
        shape = AzButtonShape.RECTANGLE,
    )
}

private fun AzphaltBrush.hasRoute(sensor: BrushSensor, parameter: BrushParameter): Boolean =
    dynamics.any { it.sensor == sensor && it.parameter == parameter }

private fun AzphaltBrush.toggleRoute(binding: BrushSensorBinding): AzphaltBrush {
    val existing = dynamics.filterNot {
        it.sensor == binding.sensor && it.parameter == binding.parameter
    }
    return copy(
        dynamics = if (existing.size == dynamics.size) existing + binding else existing,
    )
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
 * A test stroke rendered with the same dab placement as the real engine. When the brush has sensor
 * routes, the preview feeds a synthetic phone/stylus gesture whose pressure and tilt increase along
 * the stroke, so a one-tap dynamics preset is visible immediately rather than becoming a mystery
 * setting that only reveals itself after the window closes.
 */
@Composable
private fun BrushPreview(brush: AzphaltBrush, color: Color) {
    Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
        // Capture the CanvasScope dimensions before entering buildList below. Inside buildList the
        // receiver is a MutableList, whose `size` would otherwise shadow CanvasScope.size.
        val canvasWidth = size.width
        val canvasHeight = size.height
        val diameter = canvasHeight / 3f
        val dabs = if (brush.dynamics.isEmpty()) {
            // Interleaved [x0,y0,x1,y1,…] — BrushStamps' legacy/static convention.
            val path = ArrayList<Float>(SAMPLES * 2)
            for (i in 0 until SAMPLES) {
                val t = i / (SAMPLES - 1f)
                path.add(diameter + t * (canvasWidth - diameter * 2f))
                path.add(canvasHeight / 2f + sin(t * 2f * PI_F) * canvasHeight / 5f)
            }
            BrushStamps.dabs(path, diameter, brush, seed = PREVIEW_SEED)
        } else {
            val builder = BrushSampleBuilder()
            val samples = buildList {
                for (i in 0 until SAMPLES) {
                    val t = i / (SAMPLES - 1f)
                    add(
                        builder.add(
                            x = diameter + t * (canvasWidth - diameter * 2f),
                            y = canvasHeight / 2f + sin(t * 2f * PI_F) * canvasHeight / 5f,
                            uptimeMillis = i * 8L,
                            pressure = 0.15f + 0.85f * t,
                            tiltRadians = HALF_PI_F * t,
                            orientationRadians = -PI_F + 2f * PI_F * t,
                        )
                    )
                }
            }
            BrushStamps.dynamicDabs(samples, diameter, brush, seed = PREVIEW_SEED)
        }

        dabs.forEach { dab ->
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
private const val HALF_PI_F = PI_F / 2f
