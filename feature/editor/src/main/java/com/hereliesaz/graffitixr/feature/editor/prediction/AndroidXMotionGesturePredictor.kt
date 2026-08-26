package com.hereliesaz.graffitixr.feature.editor.prediction

import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.input.motionprediction.MotionEventPredictor

/**
 * Adapter around AndroidX's frame-time MotionEvent predictor. Raw MotionEvents are fed separately
 * through [recordMotionEvent]; the GesturePredictor [record] call is deliberately a no-op because
 * AndroidX owns its own input history and requires the original MotionEvent stream.
 */
class AndroidXMotionGesturePredictor(
    private val view: View,
) : GesturePredictor {
    override val name: String = "androidx"
    private var predictor: MotionEventPredictor = MotionEventPredictor.newInstance(view)
    private var latestPressure: Float = 1f

    override fun reset() {
        predictor = MotionEventPredictor.newInstance(view)
        latestPressure = 1f
    }

    override fun record(sample: GestureSample) = Unit

    fun recordMotionEvent(event: MotionEvent) {
        if (event.pointerCount <= 0) return
        latestPressure = event.getPressure(event.actionIndex.coerceIn(0, event.pointerCount - 1))
        try {
            predictor.record(event)
        } catch (_: IllegalArgumentException) {
            // Compose can start observing halfway through an already-active stream after a tool or
            // layer recomposition. AndroidX quite correctly rejects that malformed history. Reset
            // instead of letting a prediction-only aid crash the drawing surface; a later ACTION_DOWN
            // starts a clean stream.
            reset()
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                predictor.record(event)
            }
        }
    }

    /** AndroidX chooses its own next-frame target time, so [targetUptimeMillis] is advisory only. */
    override fun predict(targetUptimeMillis: Long): GesturePrediction? {
        val predicted = try {
            predictor.predict()
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return try {
            if (predicted.pointerCount <= 0) return null
            val index = predicted.actionIndex.coerceIn(0, predicted.pointerCount - 1)
            GesturePrediction(
                model = name,
                position = Offset(predicted.getX(index), predicted.getY(index)),
                targetUptimeMillis = predicted.eventTime,
                pressure = predicted.getPressure(index).takeIf { it.isFinite() } ?: latestPressure,
            )
        } finally {
            predicted.recycle()
        }
    }
}
