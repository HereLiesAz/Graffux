package com.hereliesaz.graffitixr.feature.editor.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke

/**
 * The Jetpack Ink brush's touch surface (Settings > Jetpack Ink brush): the in-progress stroke is
 * drawn by Ink's front-buffered [InProgressStrokesView], in world coordinates ([screenToWorld] is
 * the viewport camera taken out, the same mapping every other brush point goes through), so the
 * finished [Stroke] lines up with the [com.hereliesaz.graffitixr.feature.editor.StrokeCommand] it
 * becomes. The live Ink copy stays on screen until [onStrokeFinished]'s callback says the layer
 * bitmap holding the committed stroke is published, so there's no gap between the two.
 */
@Suppress("FunctionNaming") // Composable naming.
@Composable
internal fun InkBrushCanvas(
    screenToWorld: InkAffine,
    brush: () -> Brush,
    onStrokeFinished: (stroke: Stroke, canvasSize: IntSize, onCommitted: () -> Unit) -> Unit,
    onRawMotionEvent: (MotionEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> InkTouchHost(context) },
        update = { host ->
            host.screenToWorld = screenToWorld
            host.brush = brush
            host.onStrokeFinished = onStrokeFinished
            host.onRawMotionEvent = onRawMotionEvent
        },
        modifier = modifier,
    )
}

/** A FrameLayout hosting the [InProgressStrokesView] and feeding it one pointer's stroke at a time. */
@SuppressLint("ViewConstructor")
private class InkTouchHost(context: Context) : FrameLayout(context) {
    var screenToWorld: InkAffine = InkAffine.IDENTITY
    var brush: () -> Brush = { error("brush not set") }
    var onStrokeFinished: (Stroke, IntSize, () -> Unit) -> Unit = { _, _, done -> done() }
    var onRawMotionEvent: (MotionEvent) -> Unit = {}

    private val inkView = InProgressStrokesView(context)
    private var strokeId: InProgressStrokeId? = null
    private var pointerId = MotionEvent.INVALID_POINTER_ID

    init {
        addView(inkView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inkView.addFinishedStrokesListener(
            object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    val size = IntSize(width, height)
                    for ((id, stroke) in strokes) {
                        onStrokeFinished(stroke, size) { inkView.removeFinishedStrokes(setOf(id)) }
                    }
                }
            },
        )
    }

    @SuppressLint("ClickableViewAccessibility") // A drawing surface; there is no click to perform.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        onRawMotionEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Unbuffered: every hardware sample, not one batch per frame -- the point of Ink.
                requestUnbufferedDispatch(event)
                pointerId = event.getPointerId(0)
                val toWorld = Matrix().apply { setValues(screenToWorld.toMatrixValues()) }
                strokeId = inkView.startStroke(event, pointerId, brush(), toWorld)
            }
            MotionEvent.ACTION_MOVE -> strokeId?.let { inkView.addToStroke(event, pointerId, it) }
            MotionEvent.ACTION_UP -> strokeId?.let {
                inkView.finishStroke(event, pointerId, it)
                strokeId = null
            }
            MotionEvent.ACTION_CANCEL -> strokeId?.let {
                inkView.cancelStroke(it, event)
                strokeId = null
            }
            MotionEvent.ACTION_POINTER_DOWN -> strokeId?.let {
                // A second finger is a gesture, not paint: drop the stroke rather than guess.
                inkView.cancelStroke(it, event)
                strokeId = null
            }
            else -> Unit
        }
        return true
    }
}
