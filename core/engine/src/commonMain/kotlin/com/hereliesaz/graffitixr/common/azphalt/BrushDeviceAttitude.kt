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
    /** Compare against persistent neutral values saved with the brush/configuration. */
    @SerialName("calibrated") CALIBRATED,
}

/**
 * Screen-remapped device attitude captured alongside pointer telemetry.
 *
 * The raw Android Euler channels are preserved only at the input boundary. Brush mechanics then
 * interpret them as three independent controls of a virtual brush pose: pitch/yaw lean the shaft
 * over the drawing surface, while roll twists the tip around its own shaft. Canvas rotation is a
 * separate editor transform and is never inferred from these values.
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
 * User-adjustable mapping from phone/tablet attitude to a virtual 3D brush pose.
 *
 * - pitch -> one shaft-lean axis over the drawing surface
 * - yaw   -> the orthogonal shaft-lean axis
 * - roll  -> axial twist of the brush/nib/tip itself
 *
 * This deliberately does not rotate the canvas or use stroke heading as tip orientation. A chisel
 * marker, calligraphy nib, flat brush or pencil may move in one direction while its tip stays
 * twisted and leaned in a completely different orientation.
 */
@Serializable
data class BrushDevicePresentationConfig(
    val enabled: Boolean = false,
    /** Manual shaft lean along screen X, -1..1. */
    val manualLeanX: Float = 0f,
    /** Manual shaft lean along screen Y, -1..1. */
    val manualLeanY: Float = 0f,
    /** Manual axial twist of the tip. */
    val manualTwistDeg: Float = 0f,
    /** Device yaw -> screen-X shaft lean. */
    val yawLeanCoupling: Float = 0f,
    /** Device pitch -> screen-Y shaft lean. */
    val pitchLeanCoupling: Float = 0f,
    /** Device roll -> axial tip twist. */
    val rollTwistCoupling: Float = 0f,
    /** Device pitch delta required to reach full Y lean. */
    val fullPitchRadians: Float = 0.65f,
    /** Device yaw delta required to reach full X lean. */
    val fullYawRadians: Float = 0.65f,
    /** Device roll delta required to reach maximum configured tip twist. */
    val fullRollRadians: Float = 0.8f,
    /** Maximum device-driven axial twist before manual twist is added. */
    val maxRollTwistDeg: Float = 180f,
    /**
     * All three default to persistent calibration so the user can pose the device before touchdown
     * and have that pose determine first contact. STROKE_START remains available for relative modes.
     */
    val pitchReference: BrushDeviceAttitudeReference = BrushDeviceAttitudeReference.CALIBRATED,
    val yawReference: BrushDeviceAttitudeReference = BrushDeviceAttitudeReference.CALIBRATED,
    val rollReference: BrushDeviceAttitudeReference = BrushDeviceAttitudeReference.CALIBRATED,
    /** Persistent neutral values; a Calibrate action can write the current attitude here. */
    val neutralPitchRadians: Float = 0f,
    val neutralYawRadians: Float = 0f,
    val neutralRollRadians: Float = 0f,
) {
    fun sanitized(): BrushDevicePresentationConfig = copy(
        manualLeanX = manualLeanX.coerceIn(-1f, 1f),
        manualLeanY = manualLeanY.coerceIn(-1f, 1f),
        manualTwistDeg = wrapDegrees(manualTwistDeg),
        yawLeanCoupling = yawLeanCoupling.coerceIn(-1f, 1f),
        pitchLeanCoupling = pitchLeanCoupling.coerceIn(-1f, 1f),
        rollTwistCoupling = rollTwistCoupling.coerceIn(-1f, 1f),
        fullPitchRadians = fullPitchRadians.coerceIn(0.05f, PI.toFloat()),
        fullYawRadians = fullYawRadians.coerceIn(0.05f, PI.toFloat()),
        fullRollRadians = fullRollRadians.coerceIn(0.05f, PI.toFloat()),
        maxRollTwistDeg = maxRollTwistDeg.coerceIn(0f, 180f),
        neutralPitchRadians = wrapRadians(neutralPitchRadians),
        neutralYawRadians = wrapRadians(neutralYawRadians),
        neutralRollRadians = wrapRadians(neutralRollRadians),
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
    /** Normalized screen-space shaft lean. This is independent from stroke heading. */
    val leanX: Float = 0f,
    val leanY: Float = 0f,
    /** Axial twist of the physical tip/nib around its shaft. */
    val twistDeg: Float = 0f,
)

/** Pure/replayable mapping from captured device attitude to virtual brush-tip pose. */
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
        val pitchRollConfidence = current.tiltConfidence.coerceIn(0f, 1f)
        val yawConfidence = current.yawConfidence.coerceIn(0f, 1f)

        val pitchReferenceValue = reference(
            cfg.pitchReference,
            strokeNeutral.pitchRadians,
            cfg.neutralPitchRadians,
        )
        val yawReferenceValue = reference(
            cfg.yawReference,
            strokeNeutral.yawRadians,
            cfg.neutralYawRadians,
        )
        val rollReferenceValue = reference(
            cfg.rollReference,
            strokeNeutral.rollRadians,
            cfg.neutralRollRadians,
        )

        val pitchDelta = shortestRadians(current.pitchRadians - pitchReferenceValue)
        val yawDelta = shortestRadians(current.yawRadians - yawReferenceValue)
        val rollDelta = shortestRadians(current.rollRadians - rollReferenceValue)

        val pitchT = (pitchDelta / cfg.fullPitchRadians).coerceIn(-1f, 1f) * pitchRollConfidence
        val yawT = (yawDelta / cfg.fullYawRadians).coerceIn(-1f, 1f) * yawConfidence
        val rollT = (rollDelta / cfg.fullRollRadians).coerceIn(-1f, 1f) * pitchRollConfidence

        return BrushDevicePresentationState(
            initialized = true,
            strokeNeutralAttitude = strokeNeutral,
            leanX = (cfg.manualLeanX + yawT * cfg.yawLeanCoupling).coerceIn(-1f, 1f),
            leanY = (cfg.manualLeanY + pitchT * cfg.pitchLeanCoupling).coerceIn(-1f, 1f),
            twistDeg = wrapDegrees(
                cfg.manualTwistDeg + rollT * cfg.rollTwistCoupling * cfg.maxRollTwistDeg
            ),
        )
    }

    private fun reference(
        mode: BrushDeviceAttitudeReference,
        strokeStart: Float,
        calibrated: Float,
    ): Float = if (mode == BrushDeviceAttitudeReference.STROKE_START) strokeStart else calibrated

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
