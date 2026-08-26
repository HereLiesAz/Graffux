package com.hereliesaz.graffitixr.feature.editor.prediction

import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.input.motionprediction.MotionEventPredictor

/**
 * Adapter around AndroidX's frame-time MotionEvent predictor. Raw MotionEvents are fed separately
 * through [recordMotionEvent]; the GesturePredictor [record] call is deliberately a no-op because
 * AndroidX owns its own input history and requires the original MotionEvent stream.
 *
 * MotionEventPredictor construction itself can fail when a View has no associated display (for
 * example a detached host, preview, or Robolectric). Prediction is optional, so that condition
 * disables this model instead of taking the drawing surface down with it.
 */
class AndroidXMotionGesturePredictor(
    private val view: View,
) : GesturePredictor {
    override val name: String = "androidx"
    private var predictor: MotionEventPredictor? = createPredictor()
    private var latestPressure: Float = 1f

    private fun createPredictor(): MotionEventPredictor? =
        runCatching { MotionEventPredictor.newInstance(view) }.getOrNull()

    override fun reset() {
        predictor = createPredictor()
        latestPressure = 1f
    }

    override fun record(sample: GestureSample) = Unit

    fun recordMotionEvent(event: MotionEvent) {
        if (event.pointerCount <= 0) return
        latestPressure = event.getPressure(event.actionIndex.coerceIn(0, event.pointerCount - 1))
        val active = predictor ?: return
        try {
            active.record(event)
        } catch (_: IllegalArgumentException) {
            // Compose can start observing halfway through an already-active stream after a tool or
            // layer recomposition. AndroidX correctly rejects that malformed history. Reset rather
            // than letting a prediction-only aid crash the drawing surface.
            reset()
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                runCatching { predictor?.record(event) }
            }
        }
    }

    /** AndroidX chooses its own next-frame target time, so [targetUptimeMillis] is advisory only. */
    override fun predict(targetUptimeMillis: Long): GesturePrediction? {
        val active = predictor ?: return null
        val predicted = try {
            active.predict()
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
