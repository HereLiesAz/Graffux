package com.hereliesaz.graffitixr.feature.editor.prediction

import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.input.motionprediction.MotionEventPredictor

/**
 * Adapter around AndroidX's frame-time MotionEvent predictor (TEMPORARY, ranked against Ink and
 * linear). Raw MotionEvents are fed separately through [recordMotionEvent]; the GesturePredictor
 * [record] call is deliberately a no-op because AndroidX owns its own input history and requires
 * the original MotionEvent stream.
 *
 * AndroidX predicts one point, at a time it picks itself (about the next frame). It is asked once
 * per real sample and that answer is reused for every target up to [SLACK_MS] past its own time;
 * later targets get null, so it is scored at the one horizon it actually predicts instead of the
 * same point being counted at f2-f4.
 *
 * MotionEventPredictor construction itself can fail when a View has no associated display (for
 * example a detached host, preview, or Robolectric). Prediction is optional, so that condition
 * disables this model instead of taking the drawing surface down with it.
 */
class AndroidXMotionGesturePredictor(
    private val view: View,
) : GesturePredictor {
    override val name: String = PredictionTournament.ANDROIDX
    private var predictor: MotionEventPredictor? = createPredictor()
    private var latestPressure: Float = 1f
    private var cached: GesturePrediction? = null
    private var cacheValid = false

    private fun createPredictor(): MotionEventPredictor? =
        runCatching { MotionEventPredictor.newInstance(view) }.getOrNull()

    override fun reset() {
        predictor = createPredictor()
        latestPressure = 1f
        cached = null
        cacheValid = false
    }

    override fun record(sample: GestureSample) = Unit

    fun recordMotionEvent(event: MotionEvent) {
        if (event.pointerCount <= 0) return
        latestPressure = event.getPressure(event.actionIndex.coerceIn(0, event.pointerCount - 1))
        cacheValid = false
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

    override fun predict(targetUptimeMillis: Long): GesturePrediction? {
        if (!cacheValid) {
            cached = predictNow()
            cacheValid = true
        }
        return cached?.takeIf { targetUptimeMillis <= it.targetUptimeMillis + SLACK_MS }
    }

    private fun predictNow(): GesturePrediction? {
        val predicted = try {
            predictor?.predict()
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return try {
            predicted.takeIf { it.pointerCount > 0 }?.let { event ->
                val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                GesturePrediction(
                    model = name,
                    position = Offset(event.getX(index), event.getY(index)),
                    targetUptimeMillis = event.eventTime,
                    pressure = event.getPressure(index).takeIf { it.isFinite() } ?: latestPressure,
                )
            }
        } finally {
            predicted.recycle()
        }
    }

    private companion object {
        /** Half a 60 Hz frame: a target this far past AndroidX's own time still counts as its frame. */
        const val SLACK_MS = 8L
    }
}
