package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI

@Serializable
enum class BrushDeviceAttitudeSource {
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("gameRotationVector") GAME_ROTATION_VECTOR,
    @SerialName("rotationVector") ROTATION_VECTOR,
}

@Serializable
enum class BrushDeviceAttitudeReference {
    /** Compare against the first captured attitude of this stroke. */
    @SerialName("strokeStart") STROKE_START,
    /** Compare against the persistent neutral values saved with the brush/configuration. */
    @SerialName("calibrated") CALIBRATED,
}

/**
 * Screen-remapped device attitude captured alongside pointer telemetry.
 *
 * Pitch/roll are gravity-referenced. Yaw may be either earth-referenced (rotation vector) or a
 * drifting relative heading (game rotation vector), so yaw has its own confidence. The values stay
 * separate from stylus tilt/orientation because the device frame and pointer frame are different
 * sources of artist-intent evidence.
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
 * Device couplings add a motion-driven offset around that user-selected presentation.
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
    /** Screen-roll -> left/right contact bias. */
    val rollCoupling: Float = 0f,
    /** Screen-pitch -> heel/toe contact bias. */
    val pitchCoupling: Float = 0f,
    /** Yaw -> ferrule/topology rotation. Negative values naturally provide world-lock compensation. */
    val yawCoupling: Float = 0f,
    /** Device delta required to reach full pitch-driven bias. */
    val fullPitchRadians: Float = 0.65f,
    /** Device delta required to reach full roll-driven bias. */
    val fullRollRadians: Float = 0.65f,
    /** Device yaw delta required to reach full yaw-driven rotation. */
    val fullYawRadians: Float = 0.8f,
    /** Maximum device-driven ferrule rotation before the manual rotation is added. */
    val maxYawRotationDeg: Float = 90f,
    /** Pitch/roll default to persistent calibration so pre-touch tablet tilt remains meaningful. */
    val pitchRollReference: BrushDeviceAttitudeReference = BrushDeviceAttitudeReference.CALIBRATED,
    /** Yaw defaults stroke-relative because game-rotation heading is arbitrary and may drift. */
    val yawReference: BrushDeviceAttitudeReference = BrushDeviceAttitudeReference.STROKE_START,
    /** Persistent neutral values; a future Calibrate action can write the current attitude here. */
    val neutralPitchRadians: Float = 0f,
    val neutralRollRadians: Float = 0f,
    val neutralYawRadians: Float = 0f,
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
        neutralPitchRadians = wrapRadians(neutralPitchRadians),
        neutralRollRadians = wrapRadians(neutralRollRadians),
        neutralYawRadians = wrapRadians(neutralYawRadians),
    )

    companion object {
        private fun wrapDegrees(value: Float): Float {
            var v = value % 360f
            if (v > 180f) v -= 360f
            if (v < -180f) v += 360f
            return v
        }

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

data class BrushDevicePresentationState(
    val initialized: Boolean = false,
    /** First device attitude of the stroke, used only by axes configured as STROKE_START. */
    val strokeNeutralAttitude: BrushDeviceAttitude = BrushDeviceAttitude(),
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
        val strokeNeutral = if (previous.initialized) previous.strokeNeutralAttitude else current
        val tiltConfidence = current.tiltConfidence.coerceIn(0f, 1f)
        val yawConfidence = current.yawConfidence.coerceIn(0f, 1f)

        val pitchReference = if (cfg.pitchRollReference == BrushDeviceAttitudeReference.STROKE_START) {
            strokeNeutral.pitchRadians
        } else {
            cfg.neutralPitchRadians
        }
        val rollReference = if (cfg.pitchRollReference == BrushDeviceAttitudeReference.STROKE_START) {
            strokeNeutral.rollRadians
        } else {
            cfg.neutralRollRadians
        }
        val yawReference = if (cfg.yawReference == BrushDeviceAttitudeReference.STROKE_START) {
            strokeNeutral.yawRadians
        } else {
            cfg.neutralYawRadians
        }

        val pitchDelta = shortestRadians(current.pitchRadians - pitchReference)
        val rollDelta = shortestRadians(current.rollRadians - rollReference)
        val yawDelta = shortestRadians(current.yawRadians - yawReference)

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
            strokeNeutralAttitude = strokeNeutral,
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
