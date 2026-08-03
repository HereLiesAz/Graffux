// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/LayerOptionsDialog.kt
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
    // Brackets a slider's drag so each drag pushes one undo checkpoint and persists on release —
    // see EditorViewModel.onLayerEditStart/onLayerEditEnd. A stock Material Slider has no drag-start
    // callback, so the flag below detects it manually.
    onEditStart: () -> Unit,
    onEditCommit: () -> Unit,
    /**
     * Auto-layout gap, non-null only when this layer actually has children to space out. It moved
     * here from a rail slider: it is a property of this layer, and this is the window that holds
     * this layer's properties. The rail had it filed under a separate "Layout" group that only
     * appeared under the same condition.
     */
    autoLayoutGap: Float?,
    onGapChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val hasFill = overlay.shapes.any { it.hasFill }
    // True from the first onValueChange of a drag until onValueChangeFinished — a stock Slider
    // reports neither "drag started" nor how many fingers are still down, so this is the only way
    // to fire onEditStart exactly once per drag instead of once per emitted value.
    var isDraggingGap by remember { mutableStateOf(false) }
    FloatingWindow(title = "Edit", onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Opacity, Blend and Alpha Lock are deliberately NOT here.
            //
            // Each of them had a second home that was at least as reachable: layer opacity is a knob
            // in the Adjust panel, Blend is an item in the Effects palette, and Alpha Lock is in the
            // layer row's own menu and on the quick-menu wedge. Three of them here made this window a
            // second control surface for the same three values, and they had already drifted — the
            // Alpha Lock button here required `shapes.isEmpty()` while the layer menu offered it on
            // anything that was not a group, so one layer could be offered the lock in one place and
            // refused it in the other.
            //
            // What is left is what only this window does: the shape and text properties of the layer
            // it was opened on.
            if (autoLayoutGap != null) {
                Text("Gap ${autoLayoutGap.roundToInt()}")
                Slider(
                    value = autoLayoutGap,
                    // Bracketed, so one drag is one undo entry and one save rather than one of each
                    // per emitted sample.
                    onValueChange = {
                        if (!isDraggingGap) { isDraggingGap = true; onEditStart() }
                        onGapChange(it)
                    },
                    onValueChangeFinished = { isDraggingGap = false; onEditCommit() },
                    valueRange = 0f..100f,
                )
            }
            if (overlay.textParams != null) {
                AzButton(text = "Edit Text", onClick = { onEditText(); onDismiss() }, shape = AzButtonShape.RECTANGLE)
            }
            AzButton(text = "Flatten All Layers", onClick = { onInvert(); onDismiss() }, shape = AzButtonShape.RECTANGLE)
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
