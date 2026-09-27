package com.hereliesaz.graffitixr.feature.editor.prediction

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.nativebridge.InkStrokePredictor

/**
 * Google Ink Stroke Modeler's Kalman predictor through core:nativebridge. Each requested frame is
 * Ink's own cubic evaluated at that exact time from its Kalman state at the latest real sample, so
 * nothing here extrapolates or rescales Ink's answer (see InkStrokePredictorJNI.cpp).
 */
class GoogleInkGesturePredictor(
    val profile: Profile = Profile.STANDARD,
) : GesturePredictor, AutoCloseable {
    /**
     * Ink Kalman tunings, ranked against each other by picking one in Settings (TEMPORARY). The
     * constants were carried over untuned, so these bracket them: how much curvature the
     * prediction keeps, and how much each sample is trusted.
     */
    @Suppress("MagicNumber") // The tuning values ARE the definition of each profile.
    enum class Profile(
        val label: String,
        val accelerationWeight: Float,
        val jerkWeight: Float,
        val measurementNoise: Double,
    ) {
        /** Ink's reference weights: curves damped toward straight, moderate smoothing. */
        STANDARD("standard", 0.5f, 0.1f, 0.026458),
        /** Straighter and smoother: less overshoot at turns, more lag when the pen curves. */
        STEADY("steady", 0.25f, 0f, 0.052916),
        /** Keeps curvature and trusts samples more: follows turns faster, overshoots more. */
        RESPONSIVE("responsive", 1f, 0.5f, 0.013229),
    }

    override val name: String = "google-ink"
    private val engine = InkStrokePredictor(profile.accelerationWeight, profile.jerkWeight, profile.measurementNoise)

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
