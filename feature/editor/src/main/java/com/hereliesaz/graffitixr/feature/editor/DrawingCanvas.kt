package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Tool

/** How long a still touch becomes the eyedropper (Procreate's touch-and-hold sample). */
private const val EYEDROP_HOLD_MS = 500L

/**
 * The touch surface for the raster tools. Procreate-shaped gesture grammar:
 *
 *  - **Drag** paints with the active tool (live preview via the view-model's working bitmap).
 *  - **Hold still** (before moving) becomes the **eyedropper**: the colour under the finger is
 *    sampled continuously and committed on lift.
 *  - **A second finger cancels the stroke** — two fingers mean a gesture (tap = undo, pinch =
 *    navigate), not painting. The partial stroke is discarded, exactly as Procreate does.
 *  - With [Tool.FILL] active, a **tap or lift** flood-fills at the finger instead of stroking.
 *
 * [gate] tells the app-level multi-finger tap observer whether a stroke was in progress, so a
 * cancelling two-finger tap doesn't ALSO fire an undo of the previous action.
 */
@Composable
fun DrawingCanvas(
    activeTool: Tool,
    brushSize: Float,
    activeColor: Color,
    layerBitmapKey: Any?,
    gate: StrokeGate,
    modifier: Modifier = Modifier,
    onStrokeStart: (Offset, IntSize) -> Unit,
    onStrokePoint: (Offset) -> Unit,
    onStrokeEnd: () -> Unit,
    onStrokeCancel: () -> Unit,
    onFillTap: (Offset, IntSize) -> Unit,
    // True while Tool.CLONE is armed but unaimed. The tool then behaves like FILL — a tap, not a
    // drag — because a clone stroke with no source has nothing to copy.
    pickingCloneSource: Boolean = false,
    onPickCloneSource: (Offset) -> Unit = {},
    onEyedropStart: (IntSize) -> Unit,
    onEyedropSample: (Offset) -> Unit,
    onEyedropEnd: (commit: Boolean) -> Unit,
) {
    // For Liquify only: collect points and show a fake preview since it can't render incrementally.
    var liquifyPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var liquifyPending by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // When the layer bitmap updates (stroke committed), clear Liquify pending path.
    LaunchedEffect(layerBitmapKey) {
        liquifyPending = emptyList()
    }

    LaunchedEffect(activeTool) {
        liquifyPoints = emptyList()
        liquifyPending = emptyList()
    }

    // Leaving composition mid-stroke (tool change, the layer getting locked, a project reload)
    // cancels the gesture loop below before it can clear the latch, and a latch left standing
    // suppresses EVERY multi-finger gesture from then on. Unwind it here as well as there.
    DisposableEffect(gate) {
        onDispose { gate.strokeActive = false }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(activeTool) {
                // Changing tool relaunches this block, killing any in-flight stroke's loop before it
                // can clear the latch. Nothing is being painted the instant this starts, so start clean.
                gate.strokeActive = false
                if (activeTool == Tool.NONE) return@pointerInput
                val slop = viewConfiguration.touchSlop

                awaitEachGesture {
                    val down = awaitFirstDown()
                    var began = false
                    var eyedrop = false
                    var cancelled = false
                    var last = down.position

                    while (true) {
                        // While nothing has started yet, wait with a timeout: a still finger
                        // produces no events, and the timeout is what flips into the eyedropper.
                        val event = if (!began && !eyedrop) {
                            withTimeoutOrNull(EYEDROP_HOLD_MS) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }

                        if (event == null) {
                            // Held still long enough → eyedropper (any tool; FILL included).
                            eyedrop = true
                            onEyedropStart(canvasSize)
                            onEyedropSample(last)
                            continue
                        }

                        // A second finger lands → this is a gesture, not painting. Cancel.
                        if (event.changes.count { it.pressed } > 1) {
                            cancelled = true
                            if (began) {
                                gate.markCancelled()
                                if (activeTool == Tool.LIQUIFY) {
                                    liquifyPoints = emptyList()
                                }
                                onStrokeCancel()
                            }
                            if (eyedrop) onEyedropEnd(false)
                            break
                        }

                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        last = change.position

                        if (eyedrop) {
                            if (!change.pressed) {
                                onEyedropEnd(true)
                                break
                            }
                            onEyedropSample(change.position)
                            change.consume()
                            continue
                        }

                        if (!change.pressed) {
                            // Lift.
                            when {
                                pickingCloneSource -> onPickCloneSource(down.position)
                                activeTool == Tool.FILL -> onFillTap(change.position, canvasSize)
                                began -> {
                                    if (activeTool == Tool.LIQUIFY && liquifyPoints.isNotEmpty()) {
                                        liquifyPending = liquifyPoints
                                        liquifyPoints = emptyList()
                                    }
                                    gate.strokeActive = false
                                    onStrokeEnd()
                                }
                                else -> {
                                    // Quick tap: a single dab.
                                    gate.strokeActive = true
                                    onStrokeStart(down.position, canvasSize)
                                    gate.strokeActive = false
                                    onStrokeEnd()
                                }
                            }
                            break
                        }

                        val moved = (change.position - down.position).getDistance() > slop
                        if (!began && moved && activeTool != Tool.FILL && !pickingCloneSource) {
                            began = true
                            gate.strokeActive = true
                            if (activeTool == Tool.LIQUIFY) {
                                liquifyPoints = listOf(down.position)
                                liquifyPending = emptyList()
                            }
                            onStrokeStart(down.position, canvasSize)
                            if (activeTool == Tool.LIQUIFY) {
                                liquifyPoints = liquifyPoints + change.position
                            }
                            onStrokePoint(change.position)
                            change.consume()
                        } else if (began) {
                            if (activeTool == Tool.LIQUIFY) {
                                liquifyPoints = liquifyPoints + change.position
                            }
                            onStrokePoint(change.position)
                            change.consume()
                        }
                    }

                    if (cancelled) gate.strokeActive = false
                }
            }
    ) {
        // Only render a fake preview for Liquify — all other tools render directly into the layer bitmap.
        val displayPath = when {
            activeTool == Tool.LIQUIFY && liquifyPoints.isNotEmpty() -> liquifyPoints
            activeTool == Tool.LIQUIFY && liquifyPending.isNotEmpty() -> liquifyPending
            else -> return@Canvas
        }

        val path = Path().apply {
            moveTo(displayPath.first().x, displayPath.first().y)
            for (i in 1 until displayPath.size) lineTo(displayPath[i].x, displayPath[i].y)
        }
        drawPath(
            path = path,
            color = Color.Magenta.copy(alpha = 0.25f),
            style = Stroke(width = brushSize, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.SrcOver
        )
    }
}
