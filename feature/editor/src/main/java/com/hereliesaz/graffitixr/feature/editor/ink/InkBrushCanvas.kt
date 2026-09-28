package com.hereliesaz.graffitixr.feature.editor.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.ExperimentalLatencyDataApi
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.authoring.latency.LatencyData
import androidx.ink.authoring.latency.LatencyDataCallback
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.hereliesaz.graffitixr.common.model.InkUtensil
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.StrokeGate
import com.hereliesaz.graffitixr.feature.editor.prediction.GestureSample
import com.hereliesaz.graffitixr.feature.editor.prediction.PredictionSession
import com.hereliesaz.graffitixr.feature.editor.prediction.rememberPredictionSession

/**
 * The Ink utensils' touch surface (see [com.hereliesaz.graffitixr.common.model.InkUtensil]): the
 * in-progress stroke is drawn by Ink's front-buffered [InProgressStrokesView], in world coordinates ([screenToWorld] is
 * the viewport camera taken out, the same mapping every other brush point goes through), so the
 * finished [Stroke] lines up with the [com.hereliesaz.graffitixr.feature.editor.StrokeCommand] it
 * becomes. The live Ink copy stays on screen until [onStrokeFinished]'s callback says the layer
 * bitmap holding the committed stroke is published, so there's no gap between the two.
 *
 * Touch arrives through Compose ([motionEventSpy]), not the Android view, so the canvas plays by
 * the same rules as DrawingCanvas: a second finger drops the stroke and stops consuming, which
 * lets the ancestor's canvasNavigation pan/zoom/rotate and multiFingerTaps undo, and [gate] is
 * held only once the stroke has moved past touch slop, so a two-finger tap is still an undo.
 *
 * [stabilizer] is read at every stroke start; a level above 0 routes the stroke's samples through
 * [InkStabilizer] (the editor's own StrokeStabilizer) into Ink's StrokeInput API instead of handing
 * it raw MotionEvents.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun InkBrushCanvas(
    screenToWorld: InkAffine,
    brush: () -> Brush,
    utensil: () -> InkUtensil,
    onStrokeFinished: (stroke: Stroke, utensil: InkUtensil, canvasSize: IntSize, onCommitted: () -> Unit) -> Unit,
    onRawMotionEvent: (MotionEvent) -> Unit,
    gate: StrokeGate,
    stabilizer: () -> Pair<Int, StabilizerAlgorithm>,
    callbacks: InkCanvasCallbacks,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val session = rememberPredictionSession(view)
    val latestCallbacks = rememberUpdatedState(callbacks)
    val host = remember { arrayOfNulls<InkTouchHost>(1) }
    val rootOffset = remember { floatArrayOf(0f, 0f) }

    DisposableEffect(session) {
        onDispose {
            latestCallbacks.value.onPredictionSessionEnd(session.tournament.rankingReport(), session.refreshRate)
        }
    }
    DisposableEffect(gate) {
        onDispose { gate.strokeActive = false }
    }

    AndroidView(
        factory = { context -> InkTouchHost(context).also { host[0] = it } },
        update = { h ->
            h.screenToWorld = screenToWorld
            h.brush = brush
            h.utensil = utensil
            h.onStrokeFinished = onStrokeFinished
            h.stabilizerSettings = stabilizer
            h.callbacks = callbacks
            h.session = session
            h.gate = gate
        },
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val p = coordinates.positionInRoot()
                rootOffset[0] = p.x
                rootOffset[1] = p.y
            }
            // Raw MotionEvents, in root coordinates, before gesture handling — every hardware
            // sample, which is what Ink wants. Offset into this surface's own space for Ink.
            .motionEventSpy { event ->
                onRawMotionEvent(event)
                val local = MotionEvent.obtain(event)
                local.offsetLocation(-rootOffset[0], -rootOffset[1])
                try {
                    host[0]?.onInput(local)
                } finally {
                    local.recycle()
                }
            }
            // Consumes the painting finger's moves once past slop, as DrawingCanvas does, and lets
            // go the moment a second finger lands so navigation and multi-finger taps get it.
            .pointerInput(session) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var painting = true
                    while (painting) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        painting = event.changes.count { it.pressed } <= 1 && change?.pressed != false
                        if (painting && change != null) {
                            if (!moved && (change.position - down.position).getDistance() > slop) moved = true
                            if (moved) change.consume()
                        }
                    }
                }
            },
    )
}

/** A FrameLayout hosting the [InProgressStrokesView] and feeding it one pointer's stroke at a time. */
@SuppressLint("ViewConstructor")
@OptIn(ExperimentalLatencyDataApi::class)
@Suppress("TooManyFunctions")
private class InkTouchHost(context: Context) : FrameLayout(context) {
    var screenToWorld: InkAffine = InkAffine.IDENTITY
    var brush: () -> Brush = { error("brush not set") }
    var utensil: () -> InkUtensil = { InkUtensil.PEN }
    var onStrokeFinished: (Stroke, InkUtensil, IntSize, () -> Unit) -> Unit = { _, _, _, done -> done() }

    // Each stroke's utensil, snapshotted at start with its brush: the selection can change between
    // lift and Ink's finished callback, and the stroke is the utensil it was started with.
    private val strokeUtensils = HashMap<InProgressStrokeId, InkUtensil>()
    var stabilizerSettings: () -> Pair<Int, StabilizerAlgorithm> = { 0 to StabilizerAlgorithm.entries.first() }
    var callbacks: InkCanvasCallbacks = InkCanvasCallbacks()
    var session: PredictionSession? = null
    var gate: StrokeGate? = null

    private val inkView = InProgressStrokesView(context)
    private var strokeId: InProgressStrokeId? = null
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var began = false

    // Stabilized route (level > 0): world-space StrokeInputs built here instead of MotionEvents.
    private val inkStabilizer = InkStabilizer()
    private var stabilizing = false
    private var stabilizerLevel = 0
    private var stabilizerAlgorithm = StabilizerAlgorithm.entries.first()
    private var strokeDownTime = 0L
    private val batch = MutableStrokeInputBatch()

    // Declared before init, which hands it to Ink. Called on Ink's render thread with a pooled
    // object, so everything is read right here.
    private val latencyCallback = object : LatencyDataCallback {
        override fun onLatencyData(data: LatencyData) {
            if (data.strokeAction == LatencyData.StrokeAction.PREDICTED_ADD) return
            val ms = InkLatency.latencyMs(
                data.osDetectsEvent, data.isOsDetectsEventSet,
                data.strokesViewGetsAction, data.isStrokesViewGetsActionSet,
                data.estimatedPixelPresentationTime,
            ) ?: return
            callbacks.onLatency(ms, data.strokeAction == LatencyData.StrokeAction.START)
        }
    }

    init {
        addView(inkView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inkView.addFinishedStrokesListener(
            object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    val size = IntSize(width, height)
                    for ((id, stroke) in strokes) {
                        val strokeUtensil = strokeUtensils.remove(id) ?: utensil()
                        onStrokeFinished(stroke, strokeUtensil, size) { inkView.removeFinishedStrokes(setOf(id)) }
                    }
                }
            },
        )
        // Ink's own per-input latency: OS event (or view receipt) -> estimated pixel presentation.
        // Ink 1.0 ships the setter @RestrictTo(LIBRARY_GROUP) and hides it from Kotlin callers, so it
        // is reached reflectively; if a later Ink moves it, the feel report just says "no data".
        runCatching {
            InProgressStrokesView::class.java
                .getMethod("setLatencyDataCallback", LatencyDataCallback::class.java)
                .invoke(inkView, latencyCallback)
        }.onFailure { android.util.Log.w("InkBrushCanvas", "Ink latency data unavailable", it) }
    }

    // Touch comes in through Compose (InkBrushCanvas's motionEventSpy). Refusing it here keeps the
    // AndroidView interop from claiming the gesture and consuming every pointer change, which would
    // starve the canvasNavigation ancestor of the second finger.
    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    /** Every MotionEvent for this surface, in its own coordinates. */
    fun onInput(event: MotionEvent) {
        val session = session
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event, session)
            MotionEvent.ACTION_MOVE -> onMove(event, session)
            MotionEvent.ACTION_UP -> {
                strokeId?.let { id ->
                    val index = event.findPointerIndex(pointerId)
                    if (index >= 0) {
                        record(session, event.getX(index), event.getY(index), event.eventTime, event.getPressure(index))
                        finish(event, index, id)
                    } else {
                        cancel(id, event)
                    }
                }
                strokeId = null
                endGesture(session, event, lifted = true)
            }
            MotionEvent.ACTION_CANCEL -> {
                strokeId?.let { cancel(it, event) }
                strokeId = null
                endGesture(session, event, lifted = false)
            }
            MotionEvent.ACTION_POINTER_DOWN -> strokeId?.let {
                // A second finger is a gesture, not paint — DrawingCanvas's rule. Drop the stroke,
                // and if it had become a stroke (past slop), tell the gate it was thrown away.
                cancel(it, event)
                strokeId = null
                if (began) gate?.markCancelled()
                began = false
            }
            else -> Unit
        }
        if (session != null && session.androidXRunning) session.androidX.recordMotionEvent(event)
    }

    private fun onDown(event: MotionEvent, session: PredictionSession?) {
        session?.tournament?.reset()
        // Unbuffered: every hardware sample, not one batch per frame -- the point of Ink.
        requestUnbufferedDispatch(event)
        pointerId = event.getPointerId(0)
        downX = event.getX(0)
        downY = event.getY(0)
        began = false
        val (level, algorithm) = stabilizerSettings()
        stabilizerLevel = level
        stabilizerAlgorithm = algorithm
        stabilizing = inkStabilizer.isActive(level)
        record(session, downX, downY, event.eventTime, event.getPressure(0))
        val startUtensil = utensil()
        strokeId = if (stabilizing) {
            inkStabilizer.reset()
            strokeDownTime = event.eventTime
            inkView.startStroke(stabilizedInput(event, 0, event.eventTime), brush(), worldToScreenMatrix())
        } else {
            val toWorld = Matrix().apply { setValues(screenToWorld.toMatrixValues()) }
            inkView.startStroke(event, pointerId, brush(), toWorld)
        }.also { strokeUtensils[it] = startUtensil }
    }

    private fun onMove(event: MotionEvent, session: PredictionSession?) {
        val id = strokeId
        val index = event.findPointerIndex(pointerId)
        if (id != null && index >= 0) moveStroke(event, session, id, index)
    }

    private fun moveStroke(event: MotionEvent, session: PredictionSession?, id: InProgressStrokeId, index: Int) {
        for (h in 0 until event.historySize) {
            record(
                session, event.getHistoricalX(index, h), event.getHistoricalY(index, h),
                event.getHistoricalEventTime(h), event.getHistoricalPressure(index, h),
            )
        }
        record(session, event.getX(index), event.getY(index), event.eventTime, event.getPressure(index))
        if (!began) {
            val dx = event.getX(index) - downX
            val dy = event.getY(index) - downY
            if (dx * dx + dy * dy > touchSlopSquared()) {
                began = true
                gate?.strokeActive = true
            }
        }
        if (!stabilizing) {
            inkView.addToStroke(event, pointerId, id)
        } else {
            addStabilized(event, index, id)
        }
    }

    private fun addStabilized(event: MotionEvent, index: Int, id: InProgressStrokeId) {
        batch.clear()
        for (h in 0 until event.historySize) {
            batch.add(stabilizedInput(event, index, event.getHistoricalEventTime(h), h))
        }
        batch.add(stabilizedInput(event, index, event.eventTime))
        inkView.addToStroke(batch, id)
    }

    private fun cancel(id: InProgressStrokeId, event: MotionEvent) {
        inkView.cancelStroke(id, event)
        strokeUtensils.remove(id)
    }

    private fun finish(event: MotionEvent, index: Int, id: InProgressStrokeId) {
        if (stabilizing) {
            inkView.finishStroke(stabilizedInput(event, index, event.eventTime), id)
        } else {
            inkView.finishStroke(event, pointerId, id)
        }
    }

    /** The stroke is over (lift or cancel): score the tournament and report, as DrawingCanvas does. */
    private fun endGesture(session: PredictionSession?, event: MotionEvent, lifted: Boolean) {
        gate?.strokeActive = false
        began = false
        if (session == null) return
        if (lifted) {
            val i = event.actionIndex.coerceIn(0, event.pointerCount - 1)
            session.tournament.endStroke(Offset(event.getX(i), event.getY(i)))
        }
        val report = session.tournament.rankingReport()
        android.util.Log.i("StrokePrediction", report)
        callbacks.onPredictionRanked(report, session.refreshRate)
    }

    /** One real sample into the prediction tournament and the feel meter's delivery series. */
    private fun record(session: PredictionSession?, x: Float, y: Float, uptimeMs: Long, pressure: Float) {
        callbacks.onSampleAccepted(uptimeMs)
        if (session == null) return
        session.tournament.record(GestureSample(Offset(x, y), uptimeMs, pressure))
        // Predictions are ranked (TEMPORARY reports) but not drawn — Ink does its own prediction.
        session.tournament.predict(uptimeMs + session.nextFrameMs, callbacks.predictionLeadMs())
    }

    /** Sample [history] (or the current one, -1) of pointer [index], stabilized, in world space. */
    private fun stabilizedInput(event: MotionEvent, index: Int, timeMs: Long, history: Int = -1): StrokeInput {
        val sx = if (history < 0) event.getX(index) else event.getHistoricalX(index, history)
        val sy = if (history < 0) event.getY(index) else event.getHistoricalY(index, history)
        val sp = if (history < 0) event.getPressure(index) else event.getHistoricalPressure(index, history)
        val wx = screenToWorld.mapX(sx, sy)
        val wy = screenToWorld.mapY(sx, sy)
        val s = inkStabilizer.apply(wx, wy, sp, stabilizerLevel, stabilizerAlgorithm)
        callbacks.onStabilized(s.lagPx)
        val tool = toolType(event.getToolType(index))
        return StrokeInput.create(
            s.x,
            s.y,
            (timeMs - strokeDownTime).coerceAtLeast(0L),
            tool,
            StrokeInput.NO_STROKE_UNIT_LENGTH,
            if (tool == InputToolType.STYLUS) s.pressure.coerceIn(0f, 1f) else StrokeInput.NO_PRESSURE,
        )
    }

    /** Ink's stroke-to-view transform on the StrokeInput route: world back to this view. */
    private fun worldToScreenMatrix(): Matrix {
        val toWorld = Matrix().apply { setValues(screenToWorld.toMatrixValues()) }
        return Matrix().also { toWorld.invert(it) }
    }

    private fun touchSlopSquared(): Float {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        return slop * slop
    }

    private fun toolType(androidTool: Int): InputToolType = when (androidTool) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> InputToolType.STYLUS
        MotionEvent.TOOL_TYPE_FINGER -> InputToolType.TOUCH
        MotionEvent.TOOL_TYPE_MOUSE -> InputToolType.MOUSE
        else -> InputToolType.UNKNOWN
    }
}
