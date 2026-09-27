package com.hereliesaz.graffitixr.feature.editor.prediction

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.nativebridge.InkStrokePredictor

/** Real Google Ink Stroke Modeler Kalman prediction, through core:nativebridge. */
class GoogleInkGesturePredictor(
    /** Length of the predicted curve; PredictionTournament sizes it to cover every ranked frame. */
    predictionIntervalMs: Long = 17L,
) : GesturePredictor, AutoCloseable {
    override val name: String = "google-ink"
    private val engine = InkStrokePredictor(predictionIntervalMs)

    private var latest: GestureSample? = null

    override fun reset() {
        engine.reset()
        latest = null
    }

    override fun record(sample: GestureSample) {
        latest = sample
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

    // The Kalman model returns a whole predicted curve, so each requested frame is read off that
    // curve (interpolated between its points) instead of stretching one endpoint. Past the curve's
    // end it continues at the final segment's velocity; before its first point it interpolates from
    // the latest real sample.
    override fun predictTrajectory(targetUptimeMillis: List<Long>): List<GesturePrediction?> {
        val anchor = latest
        val points = if (anchor == null) {
            emptyList()
        } else {
            buildList {
                add(Triple(anchor.position, anchor.uptimeMillis, anchor.pressure))
                engine.predictTrajectory().forEach { add(Triple(Offset(it.x, it.y), it.uptimeMillis, it.pressure)) }
            }.distinctBy { it.second }
        }
        // points[0] is the real anchor; fewer than two means Ink offered no curve yet.
        return targetUptimeMillis.map { target -> if (points.size < 2) null else pointOnCurve(points, target) }
    }

    private fun pointOnCurve(points: List<Triple<Offset, Long, Float>>, target: Long): GesturePrediction {
        val first = points.indexOfFirst { it.second >= target }
        val i = when {
            first > 0 -> first
            first == 0 -> 1
            else -> points.size - 1
        }
        val a = points[i - 1]
        val b = points[i]
        val f = (target - a.second).toFloat() / (b.second - a.second)
        return GesturePrediction(
            model = name,
            position = a.first + (b.first - a.first) * f,
            targetUptimeMillis = target,
            pressure = b.third,
        )
    }

    override fun close() = engine.close()
}
