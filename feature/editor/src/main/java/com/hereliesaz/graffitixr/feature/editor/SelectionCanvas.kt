package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.util.SelectionGeometry

/**
 * The touch surface for the freehand selection tool. Two gestures, chosen by where the finger
 * lands:
 *
 *  - **Outside** the current selection (or with none) → the drag **traces a new lasso**, adopted on
 *    lift. A tap that never moves **deselects**, which is how you get back to painting everywhere.
 *  - **Inside** the current selection → the drag **moves the selected pixels**. A dashed ghost of
 *    the marquee follows the finger; the pixels themselves are lifted on release, because a move is
 *    a full-bitmap lift-clear-stamp and doing that per drag frame would stutter on a large canvas.
 *
 * A second finger cancels either gesture, matching [DrawingCanvas] — two fingers mean navigate or
 * undo, and [gate] keeps that cancelling tap from also firing the undo.
 */
@Composable
fun SelectionCanvas(
    selection: Selection?,
    gate: StrokeGate,
    modifier: Modifier = Modifier,
    onSelectionEnd: (List<Offset>, IntSize) -> Unit,
    onSelectionMove: (Offset) -> Unit,
    onClearSelection: () -> Unit,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lasso by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var moveDelta by remember { mutableStateOf<Offset?>(null) }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(selection) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // The live selection is captured by the pointerInput key, so this decision is
                    // made against the selection actually on screen.
                    val moving = selection != null && SelectionGeometry.contains(selection, down.position)
                    var began = false
                    lasso = if (moving) emptyList() else listOf(down.position)
                    moveDelta = if (moving) Offset.Zero else null

                    while (true) {
                        val event = awaitPointerEvent()

                        if (event.changes.count { it.pressed } > 1) {
                            // Gesture, not selecting — drop whatever was in progress.
                            if (began) gate.markCancelled()
                            gate.strokeActive = false
                            lasso = emptyList()
                            moveDelta = null
                            break
                        }

                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue

                        if (!change.pressed) {
                            gate.strokeActive = false
                            when {
                                moving -> moveDelta?.let { if (began) onSelectionMove(it) }
                                began -> onSelectionEnd(lasso, canvasSize)
                                // A tap that never became a drag clears the selection.
                                else -> onClearSelection()
                            }
                            lasso = emptyList()
                            moveDelta = null
                            break
                        }

                        val moved = (change.position - down.position).getDistance() > slop
                        if (!began && moved) {
                            began = true
                            gate.strokeActive = true
                        }
                        if (began) {
                            if (moving) moveDelta = change.position - down.position
                            else lasso = lasso + change.position
                            change.consume()
                        }
                    }
                }
            }
    ) {
        // Live lasso: a solid thin line, so it reads as "being drawn" rather than as a committed
        // marquee (which is dashed and animated).
        if (lasso.size >= 2) {
            drawPolygon(lasso, Color.White.copy(alpha = 0.9f), 1.5.dp.toPx(), closed = false)
            drawPolygon(lasso, Color.Black.copy(alpha = 0.5f), 1.5.dp.toPx(), closed = false, offset = Offset(1f, 1f))
        }
        // Ghost of the marquee under a move-drag, previewing where the pixels will land.
        val d = moveDelta
        if (d != null && selection != null && selection.isUsable && d != Offset.Zero) {
            drawPolygon(
                selection.path.map { it + d }, Color.White.copy(alpha = 0.7f), 1.5.dp.toPx(),
                closed = true, dash = floatArrayOf(8f, 8f),
            )
        }
    }
}

/**
 * The committed selection's marching ants: a dashed outline whose dash phase animates, so an
 * active selection is unmistakable against artwork of any colour. Purely visual — no pointer input,
 * so it can sit above the touch surfaces without stealing from them.
 *
 * An inverted selection also outlines the viewport edge, because the selected region is then the
 * ring between the two and an inner outline alone would read as a normal selection.
 */
@Composable
fun SelectionMarquee(
    selection: Selection,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "marching-ants")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing)),
        label = "ant-phase",
    )
    Canvas(modifier = modifier) {
        if (!selection.isUsable) return@Canvas
        val width = 1.5.dp.toPx()
        val dash = floatArrayOf(8f, 8f)
        // Black underlay then white ants on top: legible over light and dark artwork alike.
        drawPolygon(selection.path, Color.Black.copy(alpha = 0.6f), width, closed = true)
        drawPolygon(selection.path, Color.White, width, closed = true, dash = dash, phase = phase)
        if (selection.inverted) {
            val border = listOf(
                Offset(0f, 0f), Offset(size.width, 0f), Offset(size.width, size.height), Offset(0f, size.height),
            )
            drawPolygon(border, Color.Black.copy(alpha = 0.6f), width, closed = true)
            drawPolygon(border, Color.White, width, closed = true, dash = dash, phase = phase)
        }
    }
}

/** Strokes [points] as a polyline (or closed polygon), optionally dashed and phase-shifted. */
private fun DrawScope.drawPolygon(
    points: List<Offset>,
    color: Color,
    width: Float,
    closed: Boolean,
    dash: FloatArray? = null,
    phase: Float = 0f,
    offset: Offset = Offset.Zero,
) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x + offset.x, points[0].y + offset.y)
        for (i in 1 until points.size) lineTo(points[i].x + offset.x, points[i].y + offset.y)
        if (closed) close()
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = width,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = dash?.let { PathEffect.dashPathEffect(it, phase) },
        ),
    )
}
