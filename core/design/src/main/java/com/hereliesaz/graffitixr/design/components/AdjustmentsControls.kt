package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.theme.AppStrings
import kotlin.math.roundToInt

// Undo/redo used to live here as UndoRedoRow, duplicating the AzNavRail onscreen composable's
// buttons (MainActivity.GraffuxApp) — removed in favor of that single copy.

@Composable
// Renders all adjustment knobs in a single row
fun AdjustmentsKnobsRow(
    opacity: Float,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    onOpacityChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onContrastChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onAdjustmentStart: () -> Unit,
    onAdjustmentEnd: () -> Unit,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Knob(
            value = opacity,
            onValueChange = onOpacityChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = strings.adj.opacity,
            color = MaterialTheme.colorScheme.secondary,
            valueRange = 0f..1f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        Knob(
            value = brightness,
            onValueChange = onBrightnessChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = strings.adj.brightness,
            color = MaterialTheme.colorScheme.onSurface,
            valueRange = -1f..1f,
            defaultValue = 0f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        Knob(
            value = contrast,
            onValueChange = onContrastChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = strings.adj.contrast,
            color = MaterialTheme.colorScheme.tertiary,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        Knob(
            value = saturation,
            onValueChange = onSaturationChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = strings.adj.saturation,
            color = MaterialTheme.colorScheme.primary,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
    }
}

@Composable
fun ColorBalanceKnobsRow(
    colorBalanceR: Float,
    colorBalanceG: Float,
    colorBalanceB: Float,
    onColorBalanceRChange: (Float) -> Unit,
    onColorBalanceGChange: (Float) -> Unit,
    onColorBalanceBChange: (Float) -> Unit,
    onAdjustmentStart: () -> Unit,
    onAdjustmentEnd: () -> Unit,
    strings: AppStrings,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Red Knob
        Knob(
            value = colorBalanceR,
            onValueChange = onColorBalanceRChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = strings.adj.red,
            color = Color.Red,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        // Green Knob
        Knob(
            value = colorBalanceG,
            onValueChange = onColorBalanceGChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = strings.adj.green,
            color = Color.Green,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
        // Blue Knob
        Knob(
            value = colorBalanceB,
            onValueChange = onColorBalanceBChange,
            onValueChangeStart = onAdjustmentStart,
            onValueChangeFinished = onAdjustmentEnd,
            text = strings.adj.blue,
            color = Color.Blue,
            valueRange = 0f..2f,
            defaultValue = 1f,
            valueFormatter = { "${(it * 100).roundToInt()}%" }
        )
    }
}
