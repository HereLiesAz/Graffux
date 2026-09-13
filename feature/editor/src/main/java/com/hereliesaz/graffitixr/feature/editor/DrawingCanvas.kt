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
import com.hereliesaz.graffitixr.common.azphalt.BrushInputTool
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
 * Real input is normalized here into [BrushSample] before viewport/layer transforms. Device class
 * and axis capability are preserved too: a high-quality stylus, basic stylus and finger therefore
 * enter separate telemetry interpretation paths instead of being flattened into one fake pointer.
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
    pickingCloneSource: Boolean = false,
    onPickCloneSource: (Offset) -> Unit = {},
    onEyedropStart: (IntSize) -> Unit,
    onEyedropSample: (Offset) -> Unit,
    onEyedropEnd: (commit: Boolean) -> Unit,
) {
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
    var latestTouchMajorPx by remember { mutableFloatStateOf(0f) }
    var latestTouchMinorPx by remember { mutableFloatStateOf(0f) }
    var latestInputTool by remember { mutableStateOf(BrushInputTool.UNKNOWN) }
    var latestPressureAvailable by remember { mutableStateOf(true) }
    var latestTiltAvailable by remember { mutableStateOf(false) }
    var latestOrientationAvailable by remember { mutableStateOf(false) }

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
            touchMajorPx = latestTouchMajorPx,
            touchMinorPx = latestTouchMinorPx,
            tool = latestInputTool,
            pressureAvailable = latestPressureAvailable,
            tiltAvailable = latestTiltAvailable,
            orientationAvailable = latestOrientationAvailable,
        )
    }

    LaunchedEffect(layerBitmapKey) {
        liquifyPending = emptyList()
    }

    LaunchedEffect(activeTool) {
        liquifyPoints = emptyList()
        liquifyPending = emptyList()
        predictionTail = null
    }

    DisposableEffect(gate) {
        onDispose { gate.strokeActive = false }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .motionEventSpy { event ->
                if (event.pointerCount > 0) {
                    val pointerIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                    val device = event.device
                    val source = event.source
                    fun hasAxis(axis: Int): Boolean =
                        runCatching { device?.getMotionRange(axis, source) != null }.getOrDefault(false)

                    latestInputTool = when (event.getToolType(pointerIndex)) {
                        MotionEvent.TOOL_TYPE_STYLUS,
                        MotionEvent.TOOL_TYPE_ERASER -> BrushInputTool.STYLUS
                        MotionEvent.TOOL_TYPE_FINGER -> BrushInputTool.FINGER
                        else -> BrushInputTool.UNKNOWN
                    }
                    latestPressureAvailable = hasAxis(MotionEvent.AXIS_PRESSURE)
                    latestTiltAvailable = hasAxis(MotionEvent.AXIS_TILT)
                    latestOrientationAvailable = hasAxis(MotionEvent.AXIS_ORIENTATION)
                    latestTiltRadians = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
                    latestOrientationRadians = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex)
                    latestTouchMajorPx = event.getTouchMajor(pointerIndex)
                    latestTouchMinorPx = event.getTouchMinor(pointerIndex)
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
            .pointerInput(activeTool, nextFrameMs, pickingCloneSource) {
                gate.strokeActive = false
                if (activeTool == Tool.NONE) return@pointerInput
                val slop = viewConfiguration.touchSlop

                awaitEachGesture {
                    val down = awaitFirstDown()
                    brushSampleBuilder.reset()
                    var began = false
                    var eyedrop = false
                    var cancelled = false
                    var last = down.position
                    val eyedropDeadlineMs = android.os.SystemClock.uptimeMillis() + EYEDROP_HOLD_MS

                    while (true) {
                        val event = if (!began && !eyedrop && !pickingCloneSource && activeTool != Tool.FILL) {
                            val remainingMs = eyedropDeadlineMs - android.os.SystemClock.uptimeMillis()
                            if (remainingMs <= 0L) null else withTimeoutOrNull(remainingMs) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }

                        if (event == null) {
                            predictionTail = null
                            eyedrop = true
                            onEyedropStart(canvasSize)
                            onEyedropSample(last)
                            continue
                        }

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
                            if (began) {
                                predictionTournament.record(
                                    GestureSample(change.position, change.uptimeMillis, change.pressure)
                                )
                            }
                            predictionTail = null
                            when {
                                pickingCloneSource -> onPickCloneSource(change.position)
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
                                    gate.strokeActive = true
                                    onStrokeStart(
                                        brushSampleBuilder.add(
                                            x = down.position.x,
                                            y = down.position.y,
                                            uptimeMillis = down.uptimeMillis,
                                            pressure = down.pressure,
                                            tiltRadians = latestTiltRadians,
                                            orientationRadians = latestOrientationRadians,
                                            touchMajorPx = latestTouchMajorPx,
                                            touchMinorPx = latestTouchMinorPx,
                                            tool = latestInputTool,
                                            pressureAvailable = latestPressureAvailable,
                                            tiltAvailable = latestTiltAvailable,
                                            orientationAvailable = latestOrientationAvailable,
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
