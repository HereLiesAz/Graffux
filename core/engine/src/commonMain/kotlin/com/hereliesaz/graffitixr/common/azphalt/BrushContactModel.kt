package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable
import kotlin.math.PI

private const val CONTACT_RAD_TO_DEG = 57.29578f

/**
 * Brushstroke-specific contact mechanics. This describes how the brush tip itself deforms under
 * the hand; it intentionally contains no paint, substrate, wetness, texture or colour behavior.
 *
 * Defaults are identity/off so every existing brush keeps its historical geometry unless it opts
 * into mechanical contact explicitly.
 */
@Serializable
data class BrushContactConfig(
    /** Master compatibility gate. */
    val enabled: Boolean = false,
    /**
     * Maximum fractional increase in footprint width at full pressure. 0 = no mechanical splay;
     * 0.5 = a full-pressure contact is 1.5x as wide before ordinary size dynamics/jitter.
     */
    val pressureSplay: Float = 0f,
    /**
     * How strongly stylus tilt flattens the contact ellipse. 0 = no tilt shape response; 1 drives
     * the tip-ratio multiplier toward its 0.05 safety floor as the stylus approaches flat.
     */
    val tiltFlattening: Float = 0f,
    /**
     * How strongly stylus azimuth steers the contact angle. 0 keeps the existing brush/follow-
     * stroke angle; 1 follows stylus orientation completely using shortest-angle interpolation.
     */
    val orientationFollow: Float = 0f,
) {
    fun sanitized(): BrushContactConfig = copy(
        pressureSplay = pressureSplay.coerceIn(0f, 3f),
        tiltFlattening = tiltFlattening.coerceIn(0f, 1f),
        orientationFollow = orientationFollow.coerceIn(0f, 1f),
    )

    fun isActive(): Boolean = enabled && (
        pressureSplay > 0f || tiltFlattening > 0f || orientationFollow > 0f
        )
}

/**
 * Renderer-independent instantaneous footprint mechanics resolved from one canonical [BrushSample].
 * Renderers consume the resulting dab geometry; they never reinterpret pressure/tilt themselves.
 */
data class BrushContactState(
    val widthMultiplier: Float = 1f,
    val tipRatioMultiplier: Float = 1f,
    val orientationDeg: Float = 0f,
    val orientationInfluence: Float = 0f,
    val contactFraction: Float = 1f,
    val splay: Float = 0f,
)

/** Pure deterministic first-stage brush-contact approximation. */
object BrushContactModel {
    fun resolve(sample: BrushSample, config: BrushContactConfig): BrushContactState {
        val cfg = config.sanitized()
        if (!cfg.isActive()) return BrushContactState()

        val pressure = sample.pressure.coerceIn(0f, 1f)
        val tiltT = (sample.tiltRadians / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
        val splay = cfg.pressureSplay * pressure
        val widthMultiplier = 1f + splay
        val tipRatioMultiplier = (1f - cfg.tiltFlattening * tiltT).coerceIn(0.05f, 1f)

        return BrushContactState(
            widthMultiplier = widthMultiplier,
            tipRatioMultiplier = tipRatioMultiplier,
            orientationDeg = sample.orientationRadians * CONTACT_RAD_TO_DEG,
            orientationInfluence = cfg.orientationFollow,
            contactFraction = pressure,
            splay = splay,
        )
    }

    /**
     * Steers [baseAngleDeg] toward the stylus orientation by the contact state's configured amount,
     * always taking the shortest angular route so crossing +/-180 degrees cannot cause a full spin.
     */
    fun resolveAngleDeg(baseAngleDeg: Float, state: BrushContactState): Float {
        val influence = state.orientationInfluence.coerceIn(0f, 1f)
        if (influence <= 0f) return baseAngleDeg
        val delta = wrapSignedDegrees(state.orientationDeg - baseAngleDeg)
        return baseAngleDeg + delta * influence
    }

    private fun wrapSignedDegrees(value: Float): Float {
        var v = value % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }
}
