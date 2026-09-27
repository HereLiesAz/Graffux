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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushContactPhase
import com.hereliesaz.graffitixr.common.azphalt.BrushDevicePresentationConfig
import com.hereliesaz.graffitixr.common.azphalt.BrushDevicePresentationModel
import com.hereliesaz.graffitixr.common.azphalt.BrushDevicePresentationState
import com.hereliesaz.graffitixr.common.azphalt.BrushInputTool
import com.hereliesaz.graffitixr.common.azphalt.BrushMorphology
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushSampleBuilder
import com.hereliesaz.graffitixr.common.azphalt.BrushTipGeometryConfig
import com.hereliesaz.graffitixr.common.azphalt.BrushTipTopology
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.prediction.AndroidXMotionGesturePredictor
import com.hereliesaz.graffitixr.feature.editor.prediction.GestureSample
import com.hereliesaz.graffitixr.feature.editor.prediction.LinearGesturePredictor
import com.hereliesaz.graffitixr.feature.editor.prediction.PredictionTournament
import com.hereliesaz.graffitixr.feature.editor.prediction.GoogleInkGesturePredictor
import kotlin.math.roundToLong

private const val EYEDROP_HOLD_MS = 500L
private const val NANOS_PER_SECOND = 1_000_000_000f

private const val MIN_STAMP_RADIUS_PX = 0.5f
/** Blur radius, as a fraction of the brush radius, at hardness 0 (a fully soft edge). */
private const val SOFT_EDGE_BLUR = 0.5f
private const val ALPHA_MAX = 255f
/**
 * Provisional ink gives way to real paint; if that signal never comes (a brush path that doesn't
 * report it), it still clears after this long rather than lingering over the stroke.
 */
private const val PROVISIONAL_MAX_MS = 250L

/**
 * Provisional ink: the path drawn as one round-capped stroke of the brush's diameter, its edge
 * softened by a blur that grows as hardness drops. One primitive, so overlapping parts of the path
 * never build up opacity (separate soft stamps did: they composited over each other and a soft
 * brush read as hard), and a long path is drawn whole, with no stamp budget to run out of. Composited
 * at [color]'s alpha. Never committed; redrawn every frame.
 */
private fun DrawScope.drawProvisionalStroke(path: List<Offset>, color: Color, diameter: Float, hardness: Float) {
    if (path.isEmpty()) return
    val radius = (diameter / 2f).coerceAtLeast(MIN_STAMP_RADIUS_PX)
    val softness = (1f - hardness.coerceIn(0f, 1f)) * radius * SOFT_EDGE_BLUR
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.copy(alpha = 1f).toArgb()
        alpha = (color.alpha * ALPHA_MAX).toInt()
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        // The blur spreads the edge both ways; shrink the core so the soft edge ends at `radius`.
        strokeWidth = (2f * radius - softness).coerceAtLeast(1f)
        if (softness >= 1f) {
            maskFilter = android.graphics.BlurMaskFilter(softness, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
    }
    val native = drawContext.canvas.nativeCanvas
    if (path.size == 1) {
        native.drawPoint(path[0].x, path[0].y, paint)
        return
    }
    val line = android.graphics.Path().apply {
        moveTo(path[0].x, path[0].y)
        for (i in 1 until path.size) lineTo(path[i].x, path[i].y)
    }
    native.drawPath(line, paint)
}

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
    /**
     * Optional physical brush definition for the cursor. Existing callers remain compatible; null
     * falls back to the historical round/elliptical footprint. When supplied, hover uses the same
     * morphology, bristle population and device-pose model as the mechanics layer.
     */
    activeBrushPreview: AzphaltBrush? = null,
    /** TEMPORARY: a Brush stroke ended; gets the prediction ranking report and display Hz. */
    onPredictionRanked: (report: String, refreshRateHz: Float) -> Unit = { _, _ -> },
    /** TEMPORARY: this canvas's prediction tournament is being discarded. */
    onPredictionSessionEnd: (report: String, refreshRateHz: Float) -> Unit = { _, _ -> },
    /**
     * Measured touch-to-paint lag in ms (null = not measured yet). The prediction tail reaches this
     * far ahead of the pen so it covers the real gap; see PredictionTournament.predict.
     */
    predictionLeadMs: () -> Long? = { null },
    /**
     * True once the current Brush stroke's real paint is on screen. Until then the canvas draws
     * provisional ink itself, starting at touch-down, so the dab appears on the next frame instead
     * of after the whole engine round trip.
     */
    strokePaintPresented: () -> Boolean = { false },
) {
    var liquifyPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var liquifyPending by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var brushCursorPosition by remember { mutableStateOf<Offset?>(null) }

    val view = LocalView.current
    val deviceAttitudeState = rememberDeviceAttitude(view, enabled = activeTool == Tool.BRUSH)
    val latestDeviceAttitudeState = rememberUpdatedState(deviceAttitudeState.value)
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
    // TEMPORARY: Settings > Developer can pin one predictor to run alone (see PredictionTournament).
    val predictionPrefs = view.context
        .getSharedPreferences(PredictionTournament.SOLO_PREFS, android.content.Context.MODE_PRIVATE)
    val soloModel = predictionPrefs.getString(PredictionTournament.SOLO_KEY, null)?.takeIf { it.isNotBlank() }
    val inkProfile = predictionPrefs.getString(PredictionTournament.INK_PROFILE_KEY, null)
        .let { saved -> GoogleInkGesturePredictor.Profile.entries.firstOrNull { it.label == saved } }
        ?: GoogleInkGesturePredictor.Profile.STANDARD
    // Google Ink draws the tail; linear covers the first samples of a stroke. AndroidX is ranked
    // alongside (TEMPORARY) and only draws the tail when run solo, since it predicts a single frame.
    val androidXPredictor = remember(view) { AndroidXMotionGesturePredictor(view) }
    val predictionTournament = remember(view, soloModel, inkProfile) {
        PredictionTournament(
            listOf(LinearGesturePredictor(), androidXPredictor),
            soloModel = soloModel,
            inkProfile = inkProfile,
        )
    }
    val androidXRunning = PredictionTournament.ANDROIDX in predictionTournament.activeModels
    // Provisional ink: the real samples since touch-down, drawn here until the engine's own paint
    // shows (or PROVISIONAL_MAX_MS passes). Presentation only.
    var provisionalInk by remember { mutableStateOf<List<Offset>?>(null) }
    var provisionalSinceMs by remember { mutableLongStateOf(0L) }
    // The editor's "paint presented" flag belongs to the previous stroke until onStrokeStart runs
    // for this one (after touch slop), so it's only trusted once this is set.
    var provisionalArmed by remember { mutableStateOf(false) }
    val latestStrokePaintPresented = rememberUpdatedState(strokePaintPresented)
    fun clearProvisionalInk() {
        provisionalInk = null
        provisionalArmed = false
    }
    // TEMPORARY: UI frame pacing while a Brush stroke is down, appended to the reports.
    val frameMeter = remember(refreshRate) { FrameIntervalMeter((NANOS_PER_SECOND / refreshRate).toLong()) }
    var brushStrokeDown by remember { mutableStateOf(false) }
    LaunchedEffect(brushStrokeDown) {
        if (!brushStrokeDown) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) frameMeter.add(now - last)
                last = now
                if (provisionalInk != null) {
                    val expired = android.os.SystemClock.uptimeMillis() - provisionalSinceMs > PROVISIONAL_MAX_MS
                    if (expired || (provisionalArmed && latestStrokePaintPresented.value())) clearProvisionalInk()
                }
            }
        }
    }
    fun fullReport() = predictionTournament.rankingReport() + "\n" + frameMeter.report()
    val latestOnPredictionSessionEnd = rememberUpdatedState(onPredictionSessionEnd)
    DisposableEffect(predictionTournament) {
        onDispose { latestOnPredictionSessionEnd.value(fullReport(), refreshRate) }
    }

    fun recordRealPoint(
        position: Offset,
        uptimeMillis: Long,
        pressure: Float,
        contactPhase: BrushContactPhase = BrushContactPhase.CONTACT,
    ): BrushSample {
        predictionTournament.record(GestureSample(position, uptimeMillis, pressure))
        // Predictions are ranked (TEMPORARY reports) but no longer drawn.
        predictionTournament.predict(uptimeMillis + nextFrameMs, predictionLeadMs())
        provisionalInk = provisionalInk?.plus(position)
        val sample = brushSampleBuilder.add(
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
        return sample.copy(
            telemetry = sample.telemetry.copy(
                contactPhase = contactPhase,
                deviceAttitude = latestDeviceAttitudeState.value,
            ),
        )
    }

    LaunchedEffect(layerBitmapKey) {
        liquifyPending = emptyList()
    }

    LaunchedEffect(activeTool) {
        liquifyPoints = emptyList()
        liquifyPending = emptyList()
        if (activeTool != Tool.BRUSH) brushCursorPosition = null
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

                    if (activeTool == Tool.BRUSH) {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_HOVER_ENTER,
                            MotionEvent.ACTION_HOVER_MOVE,
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_MOVE -> {
                                brushCursorPosition = Offset(
                                    event.getX(pointerIndex),
                                    event.getY(pointerIndex),
                                )
                            }
                            MotionEvent.ACTION_HOVER_EXIT -> brushCursorPosition = null
                        }
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    brushStrokeDown = activeTool == Tool.BRUSH
                    predictionTournament.reset()
                    brushSampleBuilder.reset()
                    // Ink under the finger/pen now, from the raw down event, before touch slop
                    // decides this is a stroke. Cleared if it turns into a hold, pinch or tap-off.
                    clearProvisionalInk()
                    if (activeTool == Tool.BRUSH && !pickingCloneSource) {
                        val i = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                        provisionalInk = listOf(Offset(event.getX(i), event.getY(i)))
                        provisionalSinceMs = event.eventTime
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    clearProvisionalInk()
                    brushStrokeDown = false
                    // Session-long per-horizon ranking of every predictor (frames 1-4 ahead).
                    // `adb logcat -s StrokePrediction` to read it.
                    if (activeTool == Tool.BRUSH) {
                        // Score what was still waiting against where the pen lifted (CANCEL has no
                        // real lift point, so those are dropped rather than scored).
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            val i = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                            predictionTournament.endStroke(Offset(event.getX(i), event.getY(i)))
                        }
                        val report = fullReport()
                        android.util.Log.i("StrokePrediction", report)
                        onPredictionRanked(report, refreshRate)
                    }
                    if (latestInputTool == BrushInputTool.FINGER || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        brushCursorPosition = null
                    }
                }
                // After the ACTION_DOWN reset above, so the new stroke's history starts with its down.
                if (activeTool == Tool.BRUSH && androidXRunning) androidXPredictor.recordMotionEvent(event)
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
                            clearProvisionalInk()
                            eyedrop = true
                            onEyedropStart(canvasSize)
                            onEyedropSample(last)
                            continue
                        }

                        if (event.changes.count { it.pressed } > 1) {
                            cancelled = true
                            clearProvisionalInk()
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
                            when {
                                pickingCloneSource -> onPickCloneSource(change.position)
                                activeTool == Tool.FILL -> onFillTap(change.position, canvasSize)
                                began -> {
                                    onStrokePoint(
                                        recordRealPoint(
                                            change.position,
                                            change.uptimeMillis,
                                            change.pressure,
                                            BrushContactPhase.LIFT_OFF,
                                        )
                                    )
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
                                        recordRealPoint(
                                            down.position,
                                            down.uptimeMillis,
                                            down.pressure,
                                            BrushContactPhase.TOUCHDOWN,
                                        ),
                                        canvasSize,
                                    )
                                    onStrokePoint(
                                        recordRealPoint(
                                            change.position,
                                            change.uptimeMillis,
                                            change.pressure,
                                            BrushContactPhase.LIFT_OFF,
                                        )
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
                                recordRealPoint(
                                    down.position,
                                    down.uptimeMillis,
                                    down.pressure,
                                    BrushContactPhase.TOUCHDOWN,
                                ),
                                canvasSize,
                            )
                            provisionalArmed = true
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

        if (activeTool == Tool.BRUSH) {
            provisionalInk?.let { path ->
                drawProvisionalStroke(
                    path = path,
                    color = activeColor,
                    diameter = brushSize,
                    hardness = activeBrushPreview?.hardness ?: 1f,
                )
            }
        }

        if (activeTool == Tool.BRUSH) {
            brushCursorPosition?.let { cursor ->
                val contact = activeBrushPreview?.contact?.sanitized()
                val pose = BrushDevicePresentationModel.resolve(
                    attitude = latestDeviceAttitudeState.value,
                    previous = BrushDevicePresentationState(),
                    config = contact?.devicePresentation ?: BrushDevicePresentationConfig(),
                )
                val topology = BrushTipTopology.preview(
                    diameterPx = brushSize.coerceAtLeast(1f),
                    legacyTipRatio = activeBrushPreview?.tipRatio ?: 1f,
                    morphology = contact?.tufts?.morphology ?: BrushMorphology.CUSTOM,
                    pose = pose,
                    geometry = contact?.tipGeometry ?: BrushTipGeometryConfig(),
                )

                if (topology.hull.isNotEmpty()) {
                    val outline = Path().apply {
                        val first = topology.hull.first()
                        moveTo(cursor.x + first.first, cursor.y + first.second)
                        topology.hull.drop(1).forEach { point ->
                            lineTo(cursor.x + point.first, cursor.y + point.second)
                        }
                        close()
                    }
                    // Dark under-stroke plus bright hairline keeps the projected tip readable on
                    // both light and dark artwork without hiding the art beneath it.
                    drawPath(
                        path = outline,
                        color = Color.Black.copy(alpha = 0.72f),
                        style = Stroke(width = 3f),
                    )
                    drawPath(
                        path = outline,
                        color = Color.White.copy(alpha = 0.92f),
                        style = Stroke(width = 1.25f),
                    )
                }

                topology.cells.forEach { cell ->
                    val center = Offset(cursor.x + cell.xPx, cursor.y + cell.yPx)
                    val alpha = 0.12f + cell.contactWeight * 0.38f
                    drawCircle(
                        color = activeColor.copy(alpha = alpha.coerceIn(0.08f, 0.55f)),
                        radius = cell.radiusPx.coerceAtMost(2.5f),
                        center = center,
                    )
                }
            }
        }
    }
}
