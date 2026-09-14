package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI

private const val ATTITUDE_RAD_TO_DEG = 57.29578f

@Serializable
enum class BrushDeviceAttitudeSource {
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("gameRotationVector") GAME_ROTATION_VECTOR,
    @SerialName("rotationVector") ROTATION_VECTOR,
}

/**
 * Screen-remapped device attitude captured alongside pointer telemetry.
 *
 * Pitch/roll are gravity-referenced. Yaw may be either earth-referenced (rotation vector) or a
 * drifting relative heading (game rotation vector), so yaw has its own confidence. The brush
 * mechanics should normally use attitude relative to the start of the stroke rather than treating
 * absolute compass heading as artist intent.
 */
@Serializable
data class BrushDeviceAttitude(
    val pitchRadians: Float = 0f,
    val rollRadians: Float = 0f,
    val yawRadians: Float = 0f,
    val tiltConfidence: Float = 0f,
    val yawConfidence: Float = 0f,
    val source: BrushDeviceAttitudeSource = BrushDeviceAttitudeSource.UNAVAILABLE,
) {
    fun sanitized(): BrushDeviceAttitude = copy(
        pitchRadians = wrapRadians(pitchRadians),
        rollRadians = wrapRadians(rollRadians),
        yawRadians = wrapRadians(yawRadians),
        tiltConfidence = tiltConfidence.coerceIn(0f, 1f),
        yawConfidence = yawConfidence.coerceIn(0f, 1f),
    )

    fun blendTo(other: BrushDeviceAttitude, t: Float): BrushDeviceAttitude {
        val a = sanitized()
        val b = other.sanitized()
        val clamped = t.coerceIn(0f, 1f)
        val discrete = if (clamped < 0.5f) a else b
        return BrushDeviceAttitude(
            pitchRadians = shortestAngleLerp(a.pitchRadians, b.pitchRadians, clamped),
            rollRadians = shortestAngleLerp(a.rollRadians, b.rollRadians, clamped),
            yawRadians = shortestAngleLerp(a.yawRadians, b.yawRadians, clamped),
            tiltConfidence = lerp(a.tiltConfidence, b.tiltConfidence, clamped),
            yawConfidence = lerp(a.yawConfidence, b.yawConfidence, clamped),
            source = discrete.source,
        ).sanitized()
    }

    fun isAvailable(): Boolean =
        source != BrushDeviceAttitudeSource.UNAVAILABLE && (tiltConfidence > 0f || yawConfidence > 0f)

    companion object {
        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun shortestAngleLerp(a: Float, b: Float, t: Float): Float =
            wrapRadians(a + wrapRadians(b - a) * t)

        private fun wrapRadians(value: Float): Float {
            val pi = PI.toFloat()
            val tau = pi * 2f
            var v = value % tau
            if (v > pi) v -= tau
            if (v < -pi) v += tau
            return v
        }
    }
}

/**
 * User-adjustable mapping from device motion to the physical presentation of the brush.
 *
 * These are intentionally separate from stylus tilt/orientation. Manual biases make it possible to
 * choose the edge/heel/toe of the brush that contacts first even on hardware with no motion sensor.
 * Device couplings add a relative motion-driven offset around that user-selected presentation.
 */
@Serializable
data class BrushDevicePresentationConfig(
    val enabled: Boolean = false,
    /** -1 = left edge first, +1 = right edge first. */
    val manualLateralBias: Float = 0f,
    /** -1 = heel first, +1 = toe/front first. */
    val manualLongitudinalBias: Float = 0f,
    /** Fixed ferrule/topology rotation around the contact normal. */
    val manualRotationDeg: Float = 0f,
    /** Relative screen-roll -> left/right contact bias. */
    val rollCoupling: Float = 0f,
    /** Relative screen-pitch -> heel/toe contact bias. */
    val pitchCoupling: Float = 0f,
    /** Relative yaw -> ferrule/topology rotation. */
    val yawCoupling: Float = 0f,
    /** Device delta required to reach full pitch-driven bias. */
    val fullPitchRadians: Float = 0.65f,
    /** Device delta required to reach full roll-driven bias. */
    val fullRollRadians: Float = 0.65f,
    /** Device yaw delta required to reach full yaw-driven rotation. */
    val fullYawRadians: Float = 0.8f,
    /** Maximum device-driven ferrule rotation before the manual rotation is added. */
    val maxYawRotationDeg: Float = 90f,
) {
    fun sanitized(): BrushDevicePresentationConfig = copy(
        manualLateralBias = manualLateralBias.coerceIn(-1f, 1f),
        manualLongitudinalBias = manualLongitudinalBias.coerceIn(-1f, 1f),
        manualRotationDeg = wrapDegrees(manualRotationDeg),
        rollCoupling = rollCoupling.coerceIn(-1f, 1f),
        pitchCoupling = pitchCoupling.coerceIn(-1f, 1f),
        yawCoupling = yawCoupling.coerceIn(-1f, 1f),
        fullPitchRadians = fullPitchRadians.coerceIn(0.05f, PI.toFloat()),
        fullRollRadians = fullRollRadians.coerceIn(0.05f, PI.toFloat()),
        fullYawRadians = fullYawRadians.coerceIn(0.05f, PI.toFloat()),
        maxYawRotationDeg = maxYawRotationDeg.coerceIn(0f, 180f),
    )

    companion object {
        private fun wrapDegrees(value: Float): Float {
            var v = value % 360f
            if (v > 180f) v -= 360f
            if (v < -180f) v += 360f
            return v
        }
    }
}

data class BrushDevicePresentationState(
    val initialized: Boolean = false,
    val neutralAttitude: BrushDeviceAttitude = BrushDeviceAttitude(),
    val lateralBias: Float = 0f,
    val longitudinalBias: Float = 0f,
    val rotationDeg: Float = 0f,
)

/** Pure/replayable mapping from captured device attitude to brush-presentation intent. */
object BrushDevicePresentationModel {
    fun resolve(
        attitude: BrushDeviceAttitude,
        previous: BrushDevicePresentationState,
        config: BrushDevicePresentationConfig,
    ): BrushDevicePresentationState {
        val cfg = config.sanitized()
        if (!cfg.enabled) return BrushDevicePresentationState()

        val current = attitude.sanitized()
        val neutral = if (previous.initialized) previous.neutralAttitude else current
        val tiltConfidence = current.tiltConfidence.coerceIn(0f, 1f)
        val yawConfidence = current.yawConfidence.coerceIn(0f, 1f)

        val pitchDelta = shortestRadians(current.pitchRadians - neutral.pitchRadians)
        val rollDelta = shortestRadians(current.rollRadians - neutral.rollRadians)
        val yawDelta = shortestRadians(current.yawRadians - neutral.yawRadians)

        val pitchT = (pitchDelta / cfg.fullPitchRadians).coerceIn(-1f, 1f) * tiltConfidence
        val rollT = (rollDelta / cfg.fullRollRadians).coerceIn(-1f, 1f) * tiltConfidence
        val yawT = (yawDelta / cfg.fullYawRadians).coerceIn(-1f, 1f) * yawConfidence

        val lateral = (cfg.manualLateralBias + rollT * cfg.rollCoupling).coerceIn(-1f, 1f)
        val longitudinal = (cfg.manualLongitudinalBias + pitchT * cfg.pitchCoupling).coerceIn(-1f, 1f)
        val rotation = wrapDegrees(
            cfg.manualRotationDeg + yawT * cfg.yawCoupling * cfg.maxYawRotationDeg
        )

        return BrushDevicePresentationState(
            initialized = true,
            neutralAttitude = neutral,
            lateralBias = lateral,
            longitudinalBias = longitudinal,
            rotationDeg = rotation,
        )
    }

    private fun shortestRadians(value: Float): Float {
        val pi = PI.toFloat()
        val tau = pi * 2f
        var v = value % tau
        if (v > pi) v -= tau
        if (v < -pi) v += tau
        return v
    }

    private fun wrapDegrees(value: Float): Float {
        var v = value % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }
}
