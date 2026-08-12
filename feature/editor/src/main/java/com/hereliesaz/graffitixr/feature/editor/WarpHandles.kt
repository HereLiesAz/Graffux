package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** How close a finger must land to a handle to grab it, in dp. Generous: handles are small. */
private val GRAB_RADIUS = 28.dp

/**
 * The draggable grid for Procreate's Distort and Warp.
 *
 * Draws the handle grid over the artwork and moves whichever handle the finger lands nearest. The
 * layer's pixels are re-rendered on **release** rather than per frame ([onRelease]) — a
 * full-resolution resample every frame would stutter, and one per drag reads as immediate — so what
 * moves during the drag is this grid, and the artwork catches up when the finger lifts.
 *
 * The grid lines are drawn between grid *neighbours*, not around the outside, so a warped mesh shows
 * how each cell is bending rather than only where its outer edge went.
 */
@Composable
fun WarpHandles(
    handles: List<Offset>,
    gridSize: Int,
    onHandleMoved: (Int, Offset) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (handles.size < 4 || gridSize < 2) return

    val currentHandles by rememberUpdatedState(handles)
    Canvas(
        modifier = modifier
            .pointerInput(gridSize) {
                val grab = GRAB_RADIUS.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val snap = currentHandles
                    val index = snap.indices.minByOrNull { (snap[it] - down.position).getDistance() }
                        ?.takeIf { (snap[it] - down.position).getDistance() <= grab }
                        ?: return@awaitEachGesture
                    down.consume()
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        if (!change.pressed) {
                            // Only re-render if something actually changed; a tap that grabbed a
                            // handle and let go must not cost a full resample.
                            if (moved) onRelease()
                            break
                        }
                        if (index != null && change.position != change.previousPosition) {
                            moved = true
                            onHandleMoved(index, change.position)
                        }
                        change.consume()
                    }
                }
            }
    ) {
        val lineWidth = 1.dp.toPx()
        fun at(row: Int, col: Int) = handles[row * gridSize + col]

        // Mesh lines, black under white so they read on any artwork.
        for (pass in 0..1) {
            val color = if (pass == 0) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.9f)
            val width = if (pass == 0) lineWidth * 2.5f else lineWidth
            for (row in 0 until gridSize) {
                for (col in 0 until gridSize) {
                    if (col < gridSize - 1) drawLine(color, at(row, col), at(row, col + 1), strokeWidth = width)
                    if (row < gridSize - 1) drawLine(color, at(row, col), at(row + 1, col), strokeWidth = width)
                }
            }
        }
        // The handles themselves, filled so they read as grabbable rather than as line junctions.
        val r = 7.dp.toPx()
        handles.forEach { p ->
            drawCircle(Color.Black.copy(alpha = 0.5f), radius = r + lineWidth, center = p)
            drawCircle(Color.White, radius = r, center = p)
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = r, center = p, style = Stroke(width = lineWidth))
        }
    }
}
