package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.model.SelectionShape
import kotlinx.coroutines.delay
import com.hereliesaz.graffitixr.common.util.SelectionGeometry

/** Dash length of the marching ants, in px. */
private const val ANT_DASH = 8f

/** Steps per full dash cycle, and how long each is held. 12/s reads as motion without the cost. */
private const val ANT_STEPS = 8
private const val ANT_STEP_MS = 80L

/**
 * The touch surface for the selection tool. What a gesture means is decided by [shape], by [op], and
 * by where the finger lands:
 *
 *  - **[SelectionShape.AUTOMATIC]** → every gesture is a **tap**, handed to the magic wand. There is
 *    no drag and no move in this mode, so a wobbly tap still selects.
 *  - **Outside** the current selection (or with none) → the drag **makes a new region**, adopted on
 *    lift. [SelectionShape.FREEHAND] traces the finger; [SelectionShape.RECTANGLE] and
 *    [SelectionShape.ELLIPSE] read the drag as two opposite corners of a box and fit their figure
 *    inside it. A tap that never moves **deselects** — but only under [SelectionOp.NEW], since
 *    part-way through building a region a stray tap must not throw it away.
 *  - **Inside** the current selection, under [SelectionOp.NEW] → the drag **moves the selected
 *    pixels**. A dashed ghost of the marquee follows the finger; the pixels themselves are lifted on
 *    release, because a move is a full-bitmap lift-clear-stamp and doing that per drag frame would
 *    stutter on a large canvas. Add and Remove take the drag instead, so the interior of a selection
 *    stays somewhere you can cut a hole.
 *
 * A second finger cancels either gesture, matching [DrawingCanvas] — two fingers mean navigate or
 * undo, and [gate] keeps that cancelling tap from also firing the undo.
 */
@Composable
fun SelectionCanvas(
    selection: Selection?,
    shape: SelectionShape,
    op: SelectionOp,
    gate: StrokeGate,
    modifier: Modifier = Modifier,
    onSelectionEnd: (List<Offset>, IntSize) -> Unit,
    onSelectionMove: (Offset) -> Unit,
    onAutoSelect: (Offset, IntSize) -> Unit,
    onClearSelection: () -> Unit,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lasso by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var moveDelta by remember { mutableStateOf<Offset?>(null) }

    // Same reason as DrawingCanvas: the pointer loop below is keyed on `selection`, so committing or
    // moving a selection tears it down. If that happens mid-drag the latch never clears, and a stuck
    // latch silently disables every multi-finger gesture in the app.
    DisposableEffect(gate) {
        onDispose { gate.strokeActive = false }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            // Keyed on `shape` as well as `selection`: the loop below reads `shape` from this
            // lambda's capture, so without it a mode picked mid-session would be invisible here
            // until something else happened to relaunch the block.
            .pointerInput(selection, shape, op) {
                // Committing or moving a selection relaunches this block and kills the in-flight
                // drag's loop; nothing is in progress the instant it starts, so start clean.
                gate.strokeActive = false
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // The live selection is captured by the pointerInput key, so this decision is
                    // made against the selection actually on screen.
                    //
                    // Only under NEW. Add and Remove are about changing the region's shape, and the
                    // most obvious thing to do with Remove is cut a hole out of the middle — which
                    // starts inside the selection, exactly where the move gesture lives. Keeping
                    // move on those modes would make the interior of a selection the one place you
                    // could not edit it.
                    // Automatic works off a tap, so nothing about it is a drag — including the
                    // move gesture, which would otherwise swallow every tap landing on the region
                    // the user is trying to extend.
                    val moving = op == SelectionOp.NEW && !shape.isTap &&
                        selection != null && SelectionGeometry.contains(selection, down.position)
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
                                // A drag means nothing in a tap mode; the wand reads the point the
                                // finger went down on, so a wobbly tap still selects.
                                shape.isTap -> onAutoSelect(down.position, canvasSize)
                                began -> onSelectionEnd(lasso, canvasSize)
                                // A tap that never became a drag clears the selection — but only
                                // under NEW. Mid-way through building a region out of several
                                // pieces, a stray tap throwing the whole thing away is a much worse
                                // outcome than a tap that does nothing.
                                op == SelectionOp.NEW -> onClearSelection()
                                else -> Unit
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
                            if (moving) {
                                moveDelta = change.position - down.position
                            } else if (!shape.isTap) {
                                // Freehand accumulates; the box shapes are rebuilt from the two
                                // corners each frame, so dragging back across the start point
                                // shrinks the figure instead of leaving a trail behind it.
                                lasso = SelectionGeometry.polygonFor(
                                    shape, down.position, change.position, lasso + change.position,
                                )
                            }
                            change.consume()
                        }
                    }
                }
            }
    ) {
        // The selection being drawn: a solid thin line, so it reads as "in progress" rather than as
        // a committed marquee (which is dashed and animated). A traced lasso is drawn open, since
        // its ends have not met yet; a rectangle or ellipse is already a complete figure and drawn
        // closed, because an open one would show a gap that is not going to be there.
        if (lasso.size >= 2) {
            val closed = shape != SelectionShape.FREEHAND
            drawPolygon(lasso, Color.White.copy(alpha = 0.9f), 1.5.dp.toPx(), closed = closed)
            drawPolygon(lasso, Color.Black.copy(alpha = 0.5f), 1.5.dp.toPx(), closed = closed, offset = Offset(1f, 1f))
        }
        // Ghost of the marquee under a move-drag, previewing where the pixels will land.
        val d = moveDelta
        if (d != null && selection != null && selection.isUsable && d != Offset.Zero) {
            drawPolygon(
                selection.outline.map { it + d }, Color.White.copy(alpha = 0.7f), 1.5.dp.toPx(),
                closed = true, dash = floatArrayOf(ANT_DASH, ANT_DASH),
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
    viewportOffset: Offset = Offset.Zero,
    viewportZoom: Float = 1f,
    viewportRotation: Float = 0f,
    modifier: Modifier = Modifier,
) {
    // Stepped, not a continuous animation. An infiniteRepeatable drives this at the display's
    // refresh rate, which repainted a full-screen canvas 60-120 times a second for the entire life
    // of a selection — the ants only need to *look* like they are marching. Twelve steps a second
    // reads as continuous motion and costs an order of magnitude less power.
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ANT_STEP_MS)
            step++
        }
    }
    val phase = (step % ANT_STEPS) * (ANT_DASH * 2f / ANT_STEPS)
    Canvas(modifier = modifier) {
        if (!selection.isUsable) return@Canvas
        val width = 1.5.dp.toPx()
        val dash = floatArrayOf(ANT_DASH, ANT_DASH)
        // The merged silhouette, not one outline per ring: after an Add the user has one region and
        // expects one boundary, and drawing each ring separately would leave the seam where two
        // added shapes overlap showing as an interior line that clips nothing.
        val region = selectionOutline(selection, viewportOffset, viewportZoom, viewportRotation) ?: return@Canvas
        // Black underlay then white ants on top: legible over light and dark artwork alike.
        drawAnts(region, Color.Black.copy(alpha = 0.6f), width, null, 0f)
        drawAnts(region, Color.White, width, dash, phase)
        if (selection.inverted) {
            val border = listOf(
                Offset(0f, 0f), Offset(size.width, 0f), Offset(size.width, size.height), Offset(0f, size.height),
            )
            drawPolygon(border, Color.Black.copy(alpha = 0.6f), width, closed = true)
            drawPolygon(border, Color.White, width, closed = true, dash = dash, phase = phase)
        }
    }
}

/**
 * The selection's merged boundary in screen space, or null when it encloses nothing.
 *
 * Mirrors [SelectionMask.bitmapPath]'s combination exactly — same ring order, same union/difference
 * — so what the ants outline is what the paint is clipped to. It has to be built separately rather
 * than shared because that one works in a layer's bitmap space and this works on screen.
 *
 * [Selection] rings are recorded in world (container) space — the same space `onSelectionEnd` and
 * `onAutoSelect` convert into before the view-model ever sees a point, for the same reason the
 * warp handles are: it is the space `SelectionMask.bitmapPath` inverts, and the only space in which
 * a selection made under one camera pose still means the same region of artwork under another. This
 * overlay sits outside the viewport's `graphicsLayer` like every other touch surface, so each point
 * is pushed back through the camera here, on the way to the screen rather than off it.
 */
private fun selectionOutline(
    selection: Selection,
    viewportOffset: Offset,
    viewportZoom: Float,
    viewportRotation: Float,
): Path? {
    val region = Path()
    var any = false
    for (ring in selection.rings) {
        if (!ring.isUsable) continue
        val screenPoints = ring.path.map {
            CanvasHitTest.worldToScreen(it, viewportOffset, viewportZoom, viewportRotation)
        }
        val ringPath = Path().apply {
            // Even-odd, matching SelectionMask.bitmapPath and SelectionGeometry — so the ants
            // outline exactly the region the paint is confined to, including where a lasso
            // crossed itself.
            fillType = PathFillType.EvenOdd
            moveTo(screenPoints[0].x, screenPoints[0].y)
            for (i in 1 until screenPoints.size) lineTo(screenPoints[i].x, screenPoints[i].y)
            close()
        }
        if (!any) {
            // Cutting out of an empty region leaves it empty; nothing to seed from.
            if (!ring.additive) continue
            region.addPath(ringPath)
            any = true
        } else {
            region.op(region, ringPath, if (ring.additive) PathOperation.Union else PathOperation.Difference)
        }
    }
    return if (any) region else null
}

/** Strokes [path], optionally dashed and phase-shifted. */
private fun DrawScope.drawAnts(path: Path, color: Color, width: Float, dash: FloatArray?, phase: Float) {
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
