// FILE: core/design/src/main/java/com/hereliesaz/graffitixr/design/components/VerticalSlider.kt
package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Procreate's edge slider: a slim vertical track that fills from the bottom to the current value.
 * Dragging anywhere on it — or tapping a point — sets the value directly, so it can be worked with a
 * thumb resting on the screen edge without hunting for a handle.
 *
 * [value] and the value passed to [onValueChange] are normalized 0..1; callers map that onto whatever
 * range the underlying setting uses.
 */
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 26.dp,
    height: Dp = 132.dp,
    trackColor: Color = Color.White.copy(alpha = 0.18f),
    fillColor: Color = Color.White.copy(alpha = 0.85f),
) {
    // Track the live height in pixels so a drag can be converted to a value without measuring twice.
    var trackHeightPx by remember { mutableFloatStateOf(0f) }

    // y grows downward but the slider fills upward, hence the inversion.
    fun valueAt(y: Float): Float {
        val h = trackHeightPx
        if (h <= 0f) return value
        return (1f - (y / h)).coerceIn(0f, 1f)
    }

    Canvas(
        modifier = modifier
            .width(width)
            .height(height)
            .pointerInput(Unit) {
                detectTapGestures { offset -> onValueChange(valueAt(offset.y)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onValueChange(valueAt(change.position.y))
                }
            },
    ) {
        trackHeightPx = size.height
        val radius = CornerRadius(size.width / 2f, size.width / 2f)
        drawRoundRect(color = trackColor, size = size, cornerRadius = radius)

        val filled = (size.height * value.coerceIn(0f, 1f))
        if (filled > 0f) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(0f, size.height - filled),
                size = Size(size.width, filled),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * The pair of edge sliders Procreate puts down the side of the canvas: brush size on top, brush
 * opacity beneath it. Both are normalized 0..1 at this layer; the caller maps them onto the editor's
 * own ranges.
 */
@Composable
fun BrushEdgeSliders(
    size: Float,
    opacity: Float,
    onSizeChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        VerticalSlider(value = size, onValueChange = onSizeChange)
        VerticalSlider(value = opacity, onValueChange = onOpacityChange)
    }
}
