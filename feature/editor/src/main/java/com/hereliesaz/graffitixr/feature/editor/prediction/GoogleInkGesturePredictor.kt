package com.hereliesaz.graffitixr.feature.editor.prediction

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.nativebridge.InkStrokePredictor

/**
 * Google Ink Stroke Modeler's Kalman predictor through core:nativebridge. Each requested frame is
 * Ink's own cubic evaluated at that exact time from its Kalman state at the latest real sample, so
 * nothing here extrapolates or rescales Ink's answer (see InkStrokePredictorJNI.cpp).
 */
class GoogleInkGesturePredictor : GesturePredictor, AutoCloseable {
    override val name: String = "google-ink"
    private val engine = InkStrokePredictor()

    override fun reset() {
        engine.reset()
    }

    override fun record(sample: GestureSample) {
        engine.record(
            x = sample.position.x,
            y = sample.position.y,
            uptimeMillis = sample.uptimeMillis,
            pressure = sample.pressure,
        )
    }

    override fun predict(targetUptimeMillis: Long): GesturePrediction? =
        engine.predictAt(targetUptimeMillis)?.let {
            GesturePrediction(
                model = name,
                position = Offset(it.x, it.y),
                targetUptimeMillis = it.uptimeMillis,
                pressure = it.pressure,
            )
        }

    override fun close() = engine.close()
}
