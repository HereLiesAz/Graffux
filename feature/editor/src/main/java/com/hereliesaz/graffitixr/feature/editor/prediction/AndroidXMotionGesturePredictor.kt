package com.hereliesaz.graffitixr.feature.editor.prediction

import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.input.motionprediction.MotionEventPredictor

/**
 * Adapter around AndroidX's frame-time MotionEvent predictor. It is fed the raw MotionEvent stream
 * from the Compose host via pointerInteropFilter, but exposes the same prediction record used by the
 * pure Kotlin models so all of them can be scored together.
 */
class AndroidXMotionGesturePredictor(view: View) {
    val name: String = "androidx"
    private var predictor: MotionEventPredictor = MotionEventPredictor.newInstance(view)
    private var latestPressure: Float = 1f

    fun reset(view: View) {
        predictor = MotionEventPredictor.newInstance(view)
        latestPressure = 1f
    }

    fun record(event: MotionEvent) {
        latestPressure = event.getPressure(event.actionIndex.coerceIn(0, event.pointerCount - 1))
        predictor.record(event)
    }

    /** AndroidX chooses the prediction timestamp itself: the next frame presentation time. */
    fun predict(): GesturePrediction? {
        val predicted = predictor.predict() ?: return null
        return try {
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
