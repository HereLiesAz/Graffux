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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushSampleBuilder
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.azphalt.GrainBehavior
import com.hereliesaz.graffitixr.common.azphalt.GrainBlendMode
import com.hereliesaz.graffitixr.common.azphalt.MaskedBrushBlendMode
import com.hereliesaz.graffitixr.common.azphalt.MaskedBrushConfig
import com.hereliesaz.graffitixr.design.components.ConfirmDialog
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Krita-grade brush primitives behind a deliberately phone-first, progressively disclosed surface. */
@Composable
fun BrushStudioWindow(
    draft: AzphaltBrush,
    brushColor: Color,
    secondaryColor: Color = Color.Black,
    isSaved: Boolean,
    onEdit: ((AzphaltBrush) -> AzphaltBrush) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var showDynamics by remember { mutableStateOf(false) }
    var showColorSource by remember { mutableStateOf(false) }
    var showTexture by remember { mutableStateOf(false) }
    var showMaskedTip by remember { mutableStateOf(false) }
    var showTaper by remember { mutableStateOf(false) }

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

            BrushPreview(draft, brushColor, secondaryColor)

            ParamSlider("Spacing", draft.spacing, 0.01f..4f, asFraction = true) { v -> onEdit { it.copy(spacing = v) } }
            ParamSlider("Tip ratio", draft.tipRatio, 0.05f..1f, asFraction = true) { v -> onEdit { it.copy(tipRatio = v) } }
            AzButton(
                text = if (draft.isotropicSpacing) "Spacing: diameter" else "Spacing: ratio-aware",
                onClick = { onEdit { it.copy(isotropicSpacing = !it.isotropicSpacing) } },
                shape = AzButtonShape.RECTANGLE,
            )
            ParamSlider("Opacity", draft.opacity, 0f..1f) { v -> onEdit { it.copy(opacity = v) } }
            ParamSlider("Hardness", draft.hardness, 0f..1f) { v -> onEdit { it.copy(hardness = v) } }
            ParamSlider("Size jitter", draft.sizeJitter, 0f..1f) { v -> onEdit { it.copy(sizeJitter = v) } }
            ParamSlider("Opacity jitter", draft.opacityJitter, 0f..1f) { v -> onEdit { it.copy(opacityJitter = v) } }
            ParamSlider("Scatter", draft.scatter, 0f..2f, asFraction = true) { v -> onEdit { it.copy(scatter = v) } }
            ParamSlider("Longitudinal scatter", draft.scatterLongitudinal, 0f..2f, asFraction = true) { v ->
                onEdit { it.copy(scatterLongitudinal = v) }
            }
            ParamSlider("Spin per px", draft.rotationPerPx, -10f..10f, unit = "°") { v -> onEdit { it.copy(rotationPerPx = v) } }

            AzButton(
                text = if (showDynamics) "Dynamics ▴" else "Dynamics ▾",
                onClick = { showDynamics = !showDynamics },
                shape = AzButtonShape.RECTANGLE,
            )
            if (showDynamics) {
                Text("Input mappings (${draft.dynamics.size})", style = MaterialTheme.typography.bodySmall)
                DynamicsPreset("Pressure → Size", draft.hasRoute(BrushSensor.PRESSURE, BrushParameter.SIZE)) {
                    onEdit { it.toggleRoute(BrushSensorBinding(BrushSensor.PRESSURE, BrushParameter.SIZE, outputMin = 0.2f, outputMax = 1f)) }
                }
                DynamicsPreset("Pressure → Opacity", draft.hasRoute(BrushSensor.PRESSURE, BrushParameter.OPACITY)) {
                    onEdit { it.toggleRoute(BrushSensorBinding(BrushSensor.PRESSURE, BrushParameter.OPACITY, outputMin = 0.1f, outputMax = 1f)) }
                }
                DynamicsPreset("Speed → Thin", draft.hasRoute(BrushSensor.SPEED, BrushParameter.SIZE)) {
                    onEdit { it.toggleRoute(BrushSensorBinding(BrushSensor.SPEED, BrushParameter.SIZE, inputMin = 0f, inputMax = 2f, outputMin = 1f, outputMax = 0.45f)) }
                }
                DynamicsPreset("Speed → Spacing", draft.hasRoute(BrushSensor.SPEED, BrushParameter.SPACING)) {
                    onEdit { it.toggleRoute(BrushSensorBinding(BrushSensor.SPEED, BrushParameter.SPACING, inputMin = 0f, inputMax = 2f, outputMin = 0.65f, outputMax = 1.8f)) }
                }
                DynamicsPreset("Tilt → Rotation", draft.hasRoute(BrushSensor.TILT, BrushParameter.ROTATION)) {
                    onEdit { it.toggleRoute(BrushSensorBinding(BrushSensor.TILT, BrushParameter.ROTATION, inputMin = 0f, inputMax = HALF_PI_F, outputMin = 0f, outputMax = 180f)) }
                }
                Text("Arbitrary response curves remain one level deeper; these presets keep the phone surface fast.", style = MaterialTheme.typography.labelSmall)
            }

            AzButton(
                text = if (showColorSource) "Color Source ▴" else "Color Source ▾",
                onClick = { showColorSource = !showColorSource },
                shape = AzButtonShape.RECTANGLE,
            )
            if (showColorSource) {
                EnumButtons("Source", BrushColorSource.entries, draft.colorSource) { value ->
                    onEdit { it.copy(colorSource = value) }
                }
                if (draft.colorSource == BrushColorSource.GRADIENT) {
                    ParamSlider("Mix", draft.colorMix, 0f..1f) { value -> onEdit { it.copy(colorMix = value) } }
                    DynamicsPreset("Pressure → Mix", draft.hasRoute(BrushSensor.PRESSURE, BrushParameter.MIX)) {
                        onEdit { it.toggleRoute(BrushSensorBinding(BrushSensor.PRESSURE, BrushParameter.MIX, outputMin = 0f, outputMax = 1f)) }
                    }
                } else if (draft.colorSource == BrushColorSource.UNIFORM_RANDOM) {
                    Text("Each dab samples the foreground→background ramp from its own deterministic random stream.", style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Plain preserves the historical foreground colour exactly.", style = MaterialTheme.typography.labelSmall)
                }
            }

            AzButton(
                text = if (showTexture) "Texture ▴" else "Texture ▾",
                onClick = { showTexture = !showTexture },
                shape = AzButtonShape.RECTANGLE,
            )
            if (showTexture) {
                if (draft.grainPath == null) {
                    Text("No grain asset in this brush. Installed brush packages can provide one.", style = MaterialTheme.typography.labelSmall)
                } else {
                    ParamSlider("Strength", draft.grainStrength, 0f..1f) { v -> onEdit { it.copy(grainStrength = v) } }
                    ParamSlider("Scale", draft.grainScale, 0.1f..16f, asFraction = true) { v -> onEdit { it.copy(grainScale = v) } }
                    EnumButtons("Movement", GrainBehavior.entries, draft.grainBehavior) { value -> onEdit { it.copy(grainBehavior = value) } }
                    EnumButtons("Mode", GrainBlendMode.entries, draft.grainBlendMode) { value -> onEdit { it.copy(grainBlendMode = value) } }
                    AzButton(
                        text = if (draft.grainRandomOffsetPerStroke) "Random phase per stroke ✓" else "Random phase per stroke",
                        onClick = { onEdit { it.copy(grainRandomOffsetPerStroke = !it.grainRandomOffsetPerStroke) } },
                        shape = AzButtonShape.RECTANGLE,
                    )
                }
            }

            AzButton(
                text = if (showMaskedTip) "Masked Tip ▴" else "Masked Tip ▾",
                onClick = { showMaskedTip = !showMaskedTip },
                shape = AzButtonShape.RECTANGLE,
            )
            if (showMaskedTip) {
                val mask = draft.maskedBrush
                if (mask == null) {
                    Text("A second generated tip can mask the primary tip. Brush packages may replace it with an image tip.", style = MaterialTheme.typography.labelSmall)
                    AzButton(
                        text = "Enable Masked Tip",
                        onClick = { onEdit { it.copy(maskedBrush = MaskedBrushConfig()) } },
                        shape = AzButtonShape.RECTANGLE,
                    )
                } else {
                    ParamSlider("Mask size", mask.sizeRatio, 0.1f..8f, asFraction = true) { v -> onEdit { it.copy(maskedBrush = mask.copy(sizeRatio = v)) } }
                    ParamSlider("Mask ratio", mask.tipRatio, 0.05f..1f, asFraction = true) { v -> onEdit { it.copy(maskedBrush = mask.copy(tipRatio = v)) } }
                    ParamSlider("Mask hardness", mask.hardness, 0f..1f) { v -> onEdit { it.copy(maskedBrush = mask.copy(hardness = v)) } }
                    ParamSlider("Mask opacity", mask.opacity, 0f..1f) { v -> onEdit { it.copy(maskedBrush = mask.copy(opacity = v)) } }
                    ParamSlider("Mask flow", mask.flow, 0f..1f) { v -> onEdit { it.copy(maskedBrush = mask.copy(flow = v)) } }
                    ParamSlider("Mask scatter", mask.scatter, 0f..2f, asFraction = true) { v -> onEdit { it.copy(maskedBrush = mask.copy(scatter = v)) } }
                    EnumButtons("Combine", MaskedBrushBlendMode.entries, mask.blendMode) { value -> onEdit { it.copy(maskedBrush = mask.copy(blendMode = value)) } }
                    AzButton(
                        text = if (mask.invert) "Invert mask ✓" else "Invert mask",
                        onClick = { onEdit { it.copy(maskedBrush = mask.copy(invert = !mask.invert)) } },
                        shape = AzButtonShape.RECTANGLE,
                    )
                    AzButton(
                        text = "Remove Masked Tip",
                        onClick = { onEdit { it.copy(maskedBrush = null) } },
                        shape = AzButtonShape.RECTANGLE,
                    )
                }
            }

            AzButton(
                text = if (showTaper) "Taper ▴" else "Taper ▾",
                onClick = { showTaper = !showTaper },
                shape = AzButtonShape.RECTANGLE,
            )
            if (showTaper) {
                val taper = draft.taper
                ParamSlider("Start length", taper.startLengthPx, 0f..600f, unit = "px") { v ->
                    onEdit { it.copy(taper = taper.copy(startLengthPx = v)) }
                }
                ParamSlider("End length", taper.endLengthPx, 0f..600f, unit = "px") { v ->
                    onEdit { it.copy(taper = taper.copy(endLengthPx = v)) }
                }
                ParamSlider("Min size", taper.minSize, 0f..1f) { v -> onEdit { it.copy(taper = taper.copy(minSize = v)) } }
                ParamSlider("Min opacity", taper.minOpacity, 0f..1f) { v -> onEdit { it.copy(taper = taper.copy(minOpacity = v)) } }
                AzButton(
                    text = if (taper.liftOffSynthesizesPressure) "Finger lift-off ✓" else "Finger lift-off",
                    onClick = { onEdit { it.copy(taper = taper.copy(liftOffSynthesizesPressure = !taper.liftOffSynthesizesPressure)) } },
                    shape = AzButtonShape.RECTANGLE,
                )
                Text(
                    "Fades size/opacity near the start and end of a stroke. Finger lift-off additionally " +
                        "slows the end fade with recorded speed, so a slow lift tapers more than a fast one.",
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
private fun <T> EnumButtons(label: String, values: List<T>, selected: T, onSelected: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodySmall)
    values.forEach { value ->
        AzButton(
            text = if (value == selected) "${prettyEnum(value.toString())} ✓" else prettyEnum(value.toString()),
            onClick = { onSelected(value) },
            shape = AzButtonShape.RECTANGLE,
        )
    }
}

private fun prettyEnum(value: String): String = value.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun DynamicsPreset(label: String, active: Boolean, onClick: () -> Unit) {
    AzButton(text = if (active) "✓ $label" else label, onClick = onClick, shape = AzButtonShape.RECTANGLE)
}

private fun AzphaltBrush.hasRoute(sensor: BrushSensor, parameter: BrushParameter): Boolean =
    dynamics.any { it.sensor == sensor && it.parameter == parameter }

private fun AzphaltBrush.toggleRoute(binding: BrushSensorBinding): AzphaltBrush {
    val existing = dynamics.filterNot { it.sensor == binding.sensor && it.parameter == binding.parameter }
    return copy(dynamics = if (existing.size == dynamics.size) existing + binding else existing)
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    asFraction: Boolean = false,
    unit: String? = null,
    onChange: (Float) -> Unit,
) {
    val shown = when {
        unit != null -> "${value.roundToInt()}$unit"
        asFraction -> "${(value * 100).roundToInt() / 100f}×"
        else -> "${(value * 100).roundToInt()}%"
    }
    Text("$label  $shown", style = MaterialTheme.typography.bodySmall)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

@Composable
private fun BrushPreview(brush: AzphaltBrush, color: Color, secondaryColor: Color) {
    Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val diameter = canvasHeight / 3f
        val dabs = if (brush.dynamics.isEmpty()) {
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
            val width = dab.radius * 2f
            val height = width * dab.tipRatio
            val sourced = when (brush.colorSource) {
                BrushColorSource.PLAIN -> color
                BrushColorSource.GRADIENT -> mixPreviewColor(color, secondaryColor, dab.colorMix)
                BrushColorSource.UNIFORM_RANDOM -> mixPreviewColor(color, secondaryColor, dab.sourceRandom)
            }
            drawOval(
                color = sourced.copy(alpha = sourced.alpha * dab.alpha),
                topLeft = Offset(dab.x - width / 2f, dab.y - height / 2f),
                size = Size(width, height),
            )
        }
    }
}

private fun mixPreviewColor(a: Color, b: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t,
    )
}

private const val PREVIEW_SEED = 12345L
private const val SAMPLES = 48
private const val PI_F = 3.1415927f
private const val HALF_PI_F = PI_F / 2f
