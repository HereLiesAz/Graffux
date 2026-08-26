package com.hereliesaz.graffitixr.feature.editor.prediction

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.nativebridge.InkStrokePredictor

/** Real Google Ink Stroke Modeler Kalman prediction, through core:nativebridge. */
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

    // Google Ink's configured Kalman predictor chooses its own confidence-limited endpoint. Its
    // returned timestamp is preserved so PredictionTournament scores it when that time actually
    // arrives instead of pretending it predicted the caller's advisory horizon.
    override fun predict(targetUptimeMillis: Long): GesturePrediction? {
        val prediction = engine.predict() ?: return null
        return GesturePrediction(
            model = name,
            position = Offset(prediction.x, prediction.y),
            targetUptimeMillis = prediction.uptimeMillis,
            pressure = prediction.pressure,
        )
    }

    override fun close() = engine.close()
}
