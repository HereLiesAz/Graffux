// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/LayerOptionsDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import com.hereliesaz.graffitixr.design.theme.NavStrings
import kotlin.math.roundToInt

/**
 * Window with every option for the active layer that isn't already an always-visible rail tool —
 * which of these apply depends on the layer's own type (text vs. shape vs. plain raster). Replaces
 * the flat, conditionally-populated "Edit" rail sub-item list (was up to 9 rail buttons that
 * appeared and disappeared as the active layer changed); the rail now has a single "Edit" item
 * (shown only when a layer is active) that opens this instead.
 */
@Composable
fun LayerOptionsDialog(
    overlay: Layer,
    navStrings: NavStrings,
    onEditText: () -> Unit,
    onInvert: () -> Unit,
    onShapeSize: () -> Unit,
    onStrokeWidth: () -> Unit,
    onCornerRadius: () -> Unit,
    onPolygonSides: () -> Unit,
    onToggleFill: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    // Brackets the opacity slider's drag so each drag pushes one undo checkpoint and persists on
    // release — see EditorViewModel.onLayerEditStart/onLayerEditEnd. Mirrors the Adjust panel's
    // opacity Knob (onValueChangeStart/onValueChangeFinished); the stock Material Slider used here
    // has no drag-start callback, so [isDraggingOpacity] below detects it manually.
    onOpacityStart: () -> Unit,
    onOpacityCommit: () -> Unit,
    onBlendMode: () -> Unit,
    onToggleAlphaLock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasFill = overlay.shapes.any { it.hasFill }
    // True from the first onValueChange of a drag until onValueChangeFinished — a stock Slider
    // reports neither "drag started" nor how many fingers are still down, so this is the only way
    // to fire onOpacityStart exactly once per drag instead of once per emitted value.
    var isDraggingOpacity by remember { mutableStateOf(false) }
    FloatingWindow(title = "Edit", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Per-layer opacity + blend + alpha lock — the trio Procreate keeps one tap from
            // any layer, so they live at the top of this window rather than behind sub-dialogs.
            Text("Opacity ${(overlay.opacity * 100).roundToInt()}%")
            Slider(
                value = overlay.opacity,
                onValueChange = {
                    if (!isDraggingOpacity) { isDraggingOpacity = true; onOpacityStart() }
                    onOpacityChange(it)
                },
                onValueChangeFinished = { isDraggingOpacity = false; onOpacityCommit() },
                valueRange = 0f..1f,
            )
            AzButton(text = "Blend mode", onClick = { onBlendMode(); onDismiss() }, shape = AzButtonShape.RECTANGLE)
            if (overlay.shapes.isEmpty()) {
                AzButton(
                    text = if (overlay.alphaLock) "Alpha Lock: On" else "Alpha Lock: Off",
                    onClick = { onToggleAlphaLock() },
                    shape = AzButtonShape.RECTANGLE,
                )
            }
            if (overlay.textParams != null) {
                AzButton(text = "Edit Text", onClick = { onEditText(); onDismiss() }, shape = AzButtonShape.RECTANGLE)
            }
            AzButton(text = navStrings.invert, onClick = { onInvert(); onDismiss() }, shape = AzButtonShape.RECTANGLE)
            if (overlay.shapes.isNotEmpty()) {
                AzButton(text = "Size", onClick = { onShapeSize(); onDismiss() }, shape = AzButtonShape.RECTANGLE)
                AzButton(text = "Stroke", onClick = { onStrokeWidth(); onDismiss() }, shape = AzButtonShape.RECTANGLE)
            }
            if (overlay.shapes.any { it.kind == ShapeKind.RECTANGLE }) {
                AzButton(text = "Corners", onClick = { onCornerRadius(); onDismiss() }, shape = AzButtonShape.RECTANGLE)
            }
            if (overlay.shapes.any { it.kind == ShapeKind.POLYGON }) {
                AzButton(text = "Sides", onClick = { onPolygonSides(); onDismiss() }, shape = AzButtonShape.RECTANGLE)
            }
            if (overlay.shapes.any { it.kind != ShapeKind.LINE }) {
                AzButton(
                    text = if (hasFill) "Fill: On" else "Fill: Off",
                    onClick = { onToggleFill() },
                    shape = AzButtonShape.RECTANGLE,
                )
            }
        }
    }
}
