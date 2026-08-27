package com.hereliesaz.graffitixr.feature.editor

import android.view.MotionEvent
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
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushSampleBuilder
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.prediction.AccelerationGesturePredictor
import com.hereliesaz.graffitixr.feature.editor.prediction.AndroidXMotionGesturePredictor
import com.hereliesaz.graffitixr.feature.editor.prediction.GestureSample
import com.hereliesaz.graffitixr.feature.editor.prediction.LinearGesturePredictor
import com.hereliesaz.graffitixr.feature.editor.prediction.PredictionTournament
import kotlin.math.roundToLong

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
 *
 * Brush latency prediction is presentation-only: predictors race to extend the visible tail to the
 * next frame, but predicted points are NEVER sent to [onStrokePoint]. Only real input can enter the
 * bitmap/history path, so a bad prediction disappears on the next sample instead of becoming paint.
 *
 * Real input is normalized here into [BrushSample] before viewport/layer transforms. That mirrors
 * Krita's paint-information model: pressure, tilt, orientation, distance and speed describe the hand
 * motion that actually happened on the screen, while downstream code is free to remap only x/y into
 * world/bitmap space. Zooming the canvas therefore cannot make an identical physical gesture become
 * a different "speed" sensor value.
 */
@Composable
fun DrawingCanvas(
    activeTool: Tool,
    brushSize: Float,
    activeColor: Color,
    layerBitmapKey: Any?,
    gate: StrokeGate,
    modifier: Modifier = Modifier,
    onStrokeStart: (BrushSample, IntSize) -> Unit,
    onStrokePoint: (BrushSample) -> Unit,
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

    val view = LocalView.current
    val androidXPredictor = remember(view) { AndroidXMotionGesturePredictor(view) }
    val predictionTournament = remember(androidXPredictor) {
        PredictionTournament(
            listOf(
                LinearGesturePredictor(),
                AccelerationGesturePredictor(),
                androidXPredictor,
            )
        )
    }
    val brushSampleBuilder = remember { BrushSampleBuilder() }
    var latestTiltRadians by remember { mutableFloatStateOf(0f) }
    var latestOrientationRadians by remember { mutableFloatStateOf(0f) }

    // View.getDisplay() throws under Robolectric and can be unavailable while a real View is
    // detached. Prediction is a latency hint, not a reason to crash; 60 Hz is the conservative
    // horizon until a visual display is actually attached.
    val refreshRate = remember(view) {
        runCatching { view.display?.refreshRate }
            .getOrNull()
            ?.takeIf { it.isFinite() && it > 1f }
            ?: 60f
    }
    val nextFrameMs = (1000f / refreshRate).roundToLong().coerceIn(4L, 34L)
    var predictionTail by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }

    fun recordRealPoint(position: Offset, uptimeMillis: Long, pressure: Float): BrushSample {
        predictionTournament.record(GestureSample(position, uptimeMillis, pressure))
        val prediction = predictionTournament.predict(uptimeMillis + nextFrameMs)
        predictionTail = if (activeTool == Tool.BRUSH && prediction != null) {
            position to prediction.position
        } else {
            null
        }
        return brushSampleBuilder.add(
            x = position.x,
            y = position.y,
            uptimeMillis = uptimeMillis,
            pressure = pressure,
            tiltRadians = latestTiltRadians,
            orientationRadians = latestOrientationRadians,
        )
    }

    // When the layer bitmap updates (stroke committed), clear Liquify pending path.
    LaunchedEffect(layerBitmapKey) {
        liquifyPending = emptyList()
    }

    LaunchedEffect(activeTool) {
        liquifyPoints = emptyList()
        liquifyPending = emptyList()
        predictionTail = null
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
            // AndroidX needs the untouched MotionEvent stream. motionEventSpy observes without
            // consuming, so the Compose pointerInput grammar below remains the sole gesture owner.
            .motionEventSpy { event ->
                if (event.pointerCount > 0) {
                    val pointerIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                    latestTiltRadians = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
                    latestOrientationRadians = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex)
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    predictionTournament.reset()
                    brushSampleBuilder.reset()
                    predictionTail = null
                }
                androidXPredictor.recordMotionEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    predictionTail = null
                }
            }
            .pointerInput(activeTool, nextFrameMs) {
                // Changing tool relaunches this block, killing any in-flight stroke's loop before it
                // can clear the latch. Nothing is being painted the instant this starts, so start clean.
                gate.strokeActive = false
                if (activeTool == Tool.NONE) return@pointerInput
                val slop = viewConfiguration.touchSlop

                awaitEachGesture {
                    val down = awaitFirstDown()
                    // ACTION_DOWN normally resets this through motionEventSpy, but keep the gesture
                    // loop self-contained for tests and unusual dispatch paths where the spy is absent.
                    brushSampleBuilder.reset()
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
                            predictionTail = null
                            eyedrop = true
                            onEyedropStart(canvasSize)
                            onEyedropSample(last)
                            continue
                        }

                        // A second finger lands → this is a gesture, not painting. Cancel.
                        if (event.changes.count { it.pressed } > 1) {
                            cancelled = true
                            predictionTail = null
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
                            // Let the final real point score any prediction that matured before lift,
                            // but don't create another future tail after the stroke has ended.
                            if (began) {
                                predictionTournament.record(
                                    GestureSample(change.position, change.uptimeMillis, change.pressure)
                                )
                            }
                            predictionTail = null
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
                                    onStrokeStart(
                                        brushSampleBuilder.add(
                                            x = down.position.x,
                                            y = down.position.y,
                                            uptimeMillis = down.uptimeMillis,
                                            pressure = down.pressure,
                                            tiltRadians = latestTiltRadians,
                                            orientationRadians = latestOrientationRadians,
                                        ),
                                        canvasSize,
                                    )
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
                            onStrokeStart(
                                recordRealPoint(down.position, down.uptimeMillis, down.pressure),
                                canvasSize,
                            )
                            change.historical.forEach { hist ->
                                if (activeTool == Tool.LIQUIFY) {
                                    liquifyPoints = liquifyPoints + hist.position
                                }
                                // HistoricalChange carries no pressure of its own (Compose only
                                // batches position/time sub-samples) — the enclosing change's
                                // pressure is the closest reading available, and pressure changes
                                // slowly enough within one frame's batch that this is unnoticeable.
                                onStrokePoint(recordRealPoint(hist.position, hist.uptimeMillis, change.pressure))
                            }
                            if (activeTool == Tool.LIQUIFY) {
                                liquifyPoints = liquifyPoints + change.position
                            }
                            onStrokePoint(recordRealPoint(change.position, change.uptimeMillis, change.pressure))
                            change.consume()
                        } else if (began) {
                            change.historical.forEach { hist ->
                                if (activeTool == Tool.LIQUIFY) {
                                    liquifyPoints = liquifyPoints + hist.position
                                }
                                onStrokePoint(recordRealPoint(hist.position, hist.uptimeMillis, change.pressure))
                            }
                            if (activeTool == Tool.LIQUIFY) {
                                liquifyPoints = liquifyPoints + change.position
                            }
                            onStrokePoint(recordRealPoint(change.position, change.uptimeMillis, change.pressure))
                            change.consume()
                        }
                    }

                    if (cancelled) gate.strokeActive = false
                }
            }
    ) {
        val displayPath = when {
            activeTool == Tool.LIQUIFY && liquifyPoints.isNotEmpty() -> liquifyPoints
            activeTool == Tool.LIQUIFY && liquifyPending.isNotEmpty() -> liquifyPending
            else -> null
        }

        if (displayPath != null) {
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

        // The winning predictor's disposable tail. Deliberately translucent: it should read as the
        // same stroke reaching the pen, but a correction on the next frame should not flash like a
        // committed opaque segment being erased.
        predictionTail?.let { (real, predicted) ->
            drawLine(
                color = activeColor.copy(alpha = activeColor.alpha * 0.45f),
                start = real,
                end = predicted,
                strokeWidth = brushSize,
                cap = StrokeCap.Round,
                blendMode = BlendMode.SrcOver,
            )
        }
    }
}
