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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.Selection
import kotlinx.coroutines.delay
import com.hereliesaz.graffitixr.common.util.SelectionGeometry

/** Dash length of the marching ants, in px. */
private const val ANT_DASH = 8f

/** Steps per full dash cycle, and how long each is held. 12/s reads as motion without the cost. */
private const val ANT_STEPS = 8
private const val ANT_STEP_MS = 80L

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
    viewportOffset: Offset,
    viewportZoom: Float,
    viewportRotation: Float,
    modifier: Modifier = Modifier,
    onSelectionEnd: (List<Offset>, IntSize) -> Unit,
    onSelectionMove: (Offset) -> Unit,
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
            .pointerInput(selection) {
                // Committing or moving a selection relaunches this block and kills the in-flight
                // drag's loop; nothing is in progress the instant it starts, so start clean.
                gate.strokeActive = false
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // The live selection is captured by the pointerInput key, so this decision is
                    // made against the selection actually on screen.
                    val worldDown = com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.mapScreenToWorld(
                        listOf(down.position), viewportOffset, viewportZoom, viewportRotation
                    ).first()
                    val moving = selection != null && SelectionGeometry.contains(selection, worldDown)
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
            val screenPath = com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.mapWorldToScreen(
                selection.path, viewportOffset, viewportZoom, viewportRotation
            )
            drawPolygon(
                screenPath.map { it + d }, Color.White.copy(alpha = 0.7f), 1.5.dp.toPx(),
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
    viewportOffset: Offset,
    viewportZoom: Float,
    viewportRotation: Float,
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
        // Map from document world-space to current screen-space
        val screenPath = com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.mapWorldToScreen(
            selection.path, viewportOffset, viewportZoom, viewportRotation
        )
        // Black underlay then white ants on top: legible over light and dark artwork alike.
        drawPolygon(screenPath, Color.Black.copy(alpha = 0.6f), width, closed = true)
        drawPolygon(screenPath, Color.White, width, closed = true, dash = dash, phase = phase)
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
