// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/PathEditOverlay.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.PathEditing
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.VectorShape
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Figma's vector node editor, on canvas: anchors, bezier handles, and the drags that move them.
 *
 * All geometry lives in the layer's local space ([PathEditing]); this only converts to and from
 * screen space via [CanvasHitTest], so what's drawn and what's hit-tested can't disagree with what
 * the renderer draws.
 *
 * Interaction, matching Figma:
 * - drag an anchor to move it (its handles come along)
 * - drag a handle to bend the curve; the opposite handle mirrors, keeping the node smooth
 * - tap a segment to insert a node there, without changing the path's shape
 * - double-tap an anchor to toggle it between a corner and a smooth point
 */
@Composable
fun PathEditOverlay(
    layer: Layer,
    uiState: EditorUiState,
    onEditStart: () -> Unit,
    onMoveNode: (index: Int, dx: Float, dy: Float) -> Unit,
    onMoveHandle: (index: Int, outgoing: Boolean, x: Float, y: Float) -> Unit,
    onEditEnd: () -> Unit,
    onSelectNode: (Int?) -> Unit,
    onInsertNode: (segmentIndex: Int, t: Float) -> Unit,
    onToggleCornerSmooth: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = layer.shapes.singleOrNull()?.takeIf { it.kind == ShapeKind.PATH } ?: return
    val density = LocalDensity.current
    val touchRadiusPx = with(density) { TOUCH_RADIUS.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(layer.id, shape) {
                detectTapGestures(
                    onTap = { screen ->
                        val local = toLocal(layer, screen, size.width, size.height, uiState) ?: return@detectTapGestures
                        // Hit radius is in local units: a zoomed-in canvas must not need a
                        // proportionally larger finger.
                        val r = touchRadiusPx / localScale(layer, uiState)
                        when (val hit = PathEditing.hitTest(shape, local.x, local.y, r)) {
                            is PathEditing.Hit.NodeHit -> onSelectNode(hit.index)
                            is PathEditing.Hit.SegmentHit -> onInsertNode(hit.segmentIndex, hit.t)
                            is PathEditing.Hit.HandleHit -> onSelectNode(hit.index)
                            null -> onSelectNode(null)
                        }
                    },
                    onDoubleTap = { screen ->
                        val local = toLocal(layer, screen, size.width, size.height, uiState) ?: return@detectTapGestures
                        val r = touchRadiusPx / localScale(layer, uiState)
                        (PathEditing.hitTest(shape, local.x, local.y, r) as? PathEditing.Hit.NodeHit)
                            ?.let { onToggleCornerSmooth(it.index) }
                    },
                )
            }
            .pointerInput(layer.id, shape) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val w = size.width
                    val h = size.height
                    val startLocal = toLocal(layer, down.position, w, h, uiState) ?: return@awaitEachGesture
                    val r = touchRadiusPx / localScale(layer, uiState)
                    // Only an anchor or a handle starts a drag. Anything else falls through so the
                    // tap detector above (insert / deselect) still gets it.
                    val hit = PathEditing.hitTest(shape, startLocal.x, startLocal.y, r)
                    val target = when (hit) {
                        is PathEditing.Hit.NodeHit, is PathEditing.Hit.HandleHit -> hit
                        else -> return@awaitEachGesture
                    }

                    var previous = startLocal
                    var started = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val local = toLocal(layer, change.position, w, h, uiState) ?: continue
                        if (!started) {
                            // Bracket the whole drag as one undo step, not one per frame.
                            started = true
                            onEditStart()
                        }
                        when (target) {
                            is PathEditing.Hit.NodeHit ->
                                onMoveNode(target.index, local.x - previous.x, local.y - previous.y)
                            // A handle is positioned absolutely, so it tracks the finger exactly
                            // instead of accumulating drift over a long drag.
                            is PathEditing.Hit.HandleHit ->
                                onMoveHandle(target.index, target.outgoing, local.x, local.y)
                            else -> Unit
                        }
                        previous = local
                        change.consume()
                    }
                    if (started) onEditEnd()
                }
            },
    ) {
        val nodes = PathEditing.nodes(shape)
        val handleRadius = with(density) { HANDLE_RADIUS.toPx() }
        val anchorRadius = with(density) { ANCHOR_RADIUS.toPx() }
        val strokeWidth = with(density) { 1.5.dp.toPx() }

        fun screenOf(x: Float, y: Float) = CanvasHitTest.layerLocalToScreen(
            layer, Offset(x, y), size.width, size.height,
            uiState.viewportOffset, uiState.viewportZoom, uiState.viewportRotation,
        )

        // Handles first so anchors draw over their own stems.
        nodes.forEachIndexed { i, n ->
            if (n.isCorner) return@forEachIndexed
            val anchor = screenOf(n.x, n.y)
            listOf(
                screenOf(n.x + n.outDx, n.y + n.outDy),
                screenOf(n.x + n.inDx, n.y + n.inDy),
            ).forEach { handle ->
                drawLine(HANDLE_COLOR, anchor, handle, strokeWidth)
                drawCircle(HANDLE_COLOR, handleRadius, handle)
            }
        }
        nodes.forEachIndexed { i, n ->
            val at = screenOf(n.x, n.y)
            val selected = uiState.selectedNodeIndex == i
            // A smooth node reads as round, a corner as a filled square — the same visual grammar
            // Figma and Illustrator use, so the node type is legible without selecting it.
            if (n.isCorner) {
                val half = anchorRadius
                drawRect(
                    color = if (selected) SELECTED_COLOR else ANCHOR_COLOR,
                    topLeft = Offset(at.x - half, at.y - half),
                    size = androidx.compose.ui.geometry.Size(half * 2, half * 2),
                )
                drawRect(
                    color = Color.White,
                    topLeft = Offset(at.x - half, at.y - half),
                    size = androidx.compose.ui.geometry.Size(half * 2, half * 2),
                    style = Stroke(strokeWidth),
                )
            } else {
                drawCircle(if (selected) SELECTED_COLOR else ANCHOR_COLOR, anchorRadius, at)
                drawCircle(Color.White, anchorRadius, at, style = Stroke(strokeWidth))
            }
        }
    }
}

private fun toLocal(
    layer: Layer,
    screen: Offset,
    width: Int,
    height: Int,
    uiState: EditorUiState,
): Offset? = CanvasHitTest.screenToLayerLocal(
    layer, screen, width.toFloat(), height.toFloat(),
    uiState.viewportOffset, uiState.viewportZoom, uiState.viewportRotation,
)

/** Screen pixels per local unit, so a touch radius in dp becomes the right radius in local space. */
private fun localScale(layer: Layer, uiState: EditorUiState): Float =
    (layer.scale * uiState.viewportZoom).takeIf { it > 0f } ?: 1f

private val TOUCH_RADIUS = 22.dp
private val ANCHOR_RADIUS = 5.dp
private val HANDLE_RADIUS = 4.dp
private val ANCHOR_COLOR = Color(0xFF18A0FB)
private val SELECTED_COLOR = Color(0xFFFF6B00)
private val HANDLE_COLOR = Color(0xFF9B9B9B)
