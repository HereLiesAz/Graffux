package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import kotlin.math.roundToInt

/** Tool-specific controls; only controls meaningful to the current tool are surfaced. */
@Composable
fun ToolOptionsWindow(
    stabilizerLevel: Int,
    onSetStabilizerLevel: (Int) -> Unit,
    stabilizerAlgorithm: StabilizerAlgorithm,
    onSetStabilizerAlgorithm: (StabilizerAlgorithm) -> Unit,
    magicWandTolerance: Int?,
    onSetMagicWandTolerance: (Int) -> Unit,
    selectionFeatherPx: Float?,
    onSetSelectionFeather: (Float) -> Unit,
    brushFlow: Float?,
    onSetBrushFlow: (Float) -> Unit,
    brushOpacity: Float?,
    onSetBrushOpacity: (Float) -> Unit,
    colorSmudgeSettings: ColorSmudgeEngine.Settings?,
    onSetColorSmudgeMode: (ColorSmudgeEngine.Mode) -> Unit,
    onSetColorSmudgeRate: (Float) -> Unit,
    onSetColorSmudgeColorRate: (Float) -> Unit,
    onSetColorSmudgeChargeDecayRate: (Float) -> Unit,
    onSetColorSmudgeDilution: (Float) -> Unit,
    onSetColorSmudgeRadius: (Float) -> Unit,
    onSetColorSmudgeOpacity: (Float) -> Unit,
    onSetColorSmudgeAlphaCarry: (Boolean) -> Unit,
    symmetryMode: SymmetryMode,
    onSetSymmetryMode: (SymmetryMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var showWetMix by remember { mutableStateOf(false) }
    FloatingWindow(title = "Tool Options", onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (brushOpacity != null) {
                Text("Opacity  ${(brushOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Text("Caps how solid the stroke can get, even where it crosses itself.", style = MaterialTheme.typography.labelSmall)
                Slider(value = brushOpacity, onValueChange = onSetBrushOpacity, valueRange = 0f..1f)
            }

            if (brushFlow != null) {
                Text("Flow  ${(brushFlow * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Text("How much paint each stamp lays down.", style = MaterialTheme.typography.labelSmall)
                Slider(value = brushFlow, onValueChange = onSetBrushFlow, valueRange = 0f..1f)
            }

            colorSmudgeSettings?.let { smudge ->
                Text("Color Smudge", style = MaterialTheme.typography.bodySmall)
                ColorSmudgeEngine.Mode.entries.forEach { mode ->
                    AzButton(
                        text = if (mode == smudge.mode) "${mode.name.lowercase().replaceFirstChar { it.uppercase() }} ✓"
                        else mode.name.lowercase().replaceFirstChar { it.uppercase() },
                        onClick = { onSetColorSmudgeMode(mode) },
                        shape = AzButtonShape.RECTANGLE,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Smudge  ${(smudge.smudgeRate * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(value = smudge.smudgeRate, onValueChange = onSetColorSmudgeRate, valueRange = 0f..1f)
                Text("Color rate  ${(smudge.colorRate * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Text("Adds the active colour independently of how much existing paint is moved.", style = MaterialTheme.typography.labelSmall)
                Slider(value = smudge.colorRate, onValueChange = onSetColorSmudgeColorRate, valueRange = 0f..1f)
                AzButton(
                    text = if (showWetMix) "Wet Mix ▴" else "Wet Mix ▾",
                    onClick = { showWetMix = !showWetMix },
                    shape = AzButtonShape.RECTANGLE,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showWetMix) {
                    Text(
                        "Charge decay  ${"%.2f".format(smudge.chargeDecayRate)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "0 keeps Color rate flat for the whole stroke. Above 0, the brush runs out of paint " +
                            "with distance and settles into a pure smudge, like a real brush drying out.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Slider(
                        value = smudge.chargeDecayRate,
                        onValueChange = onSetColorSmudgeChargeDecayRate,
                        valueRange = 0f..0.2f,
                    )
                    Text("Dilution  ${(smudge.dilution * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "How much the deposited colour pre-mixes with what's already there before landing, " +
                            "instead of laying down pure Color.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Slider(value = smudge.dilution, onValueChange = onSetColorSmudgeDilution, valueRange = 0f..1f)
                }
                if (smudge.mode == ColorSmudgeEngine.Mode.DULLING) {
                    Text("Sample radius  ${"%.2f".format(smudge.smudgeRadius)}×", style = MaterialTheme.typography.bodySmall)
                    Slider(value = smudge.smudgeRadius, onValueChange = onSetColorSmudgeRadius, valueRange = 0.25f..3f)
                }
                Text("Smudge opacity  ${(smudge.opacity * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(value = smudge.opacity, onValueChange = onSetColorSmudgeOpacity, valueRange = 0f..1f)
                AzButton(
                    text = if (smudge.smearAlpha) "Carry alpha ✓" else "Preserve destination alpha",
                    onClick = { onSetColorSmudgeAlphaCarry(!smudge.smearAlpha) },
                    shape = AzButtonShape.RECTANGLE,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("Stabilize  $stabilizerLevel%", style = MaterialTheme.typography.bodySmall)
            Text("Smooths jitter out of a drag before it becomes a stroke.", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = stabilizerLevel.toFloat(),
                onValueChange = { onSetStabilizerLevel(it.roundToInt()) },
                valueRange = 0f..100f,
            )
            if (stabilizerLevel > 0) {
                StabilizerAlgorithm.entries.forEach { algo ->
                    AzButton(
                        text = if (algo == stabilizerAlgorithm) "${algo.label} ✓" else algo.label,
                        onClick = { onSetStabilizerAlgorithm(algo) },
                        shape = AzButtonShape.RECTANGLE,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (magicWandTolerance != null) {
                Text("Threshold  ${(magicWandTolerance / 255f * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                Text("How far from the tapped colour still counts as the same colour.", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = magicWandTolerance.toFloat(),
                    onValueChange = { onSetMagicWandTolerance(it.roundToInt()) },
                    valueRange = 0f..255f,
                )
            }

            if (selectionFeatherPx != null) {
                Text("Feather  ${selectionFeatherPx.roundToInt()} px", style = MaterialTheme.typography.bodySmall)
                Text("Softens the boundary of the current selection.", style = MaterialTheme.typography.labelSmall)
                Slider(value = selectionFeatherPx, onValueChange = onSetSelectionFeather, valueRange = 0f..64f)
            }

            if (symmetryMode != SymmetryMode.NONE) {
                Text("Symmetry", style = MaterialTheme.typography.bodySmall)
                SymmetryMode.entries.filter { it != SymmetryMode.NONE }.forEach { mode ->
                    AzButton(
                        text = if (mode == symmetryMode) "${mode.label} ✓" else mode.label,
                        onClick = { onSetSymmetryMode(mode) },
                        shape = AzButtonShape.RECTANGLE,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
