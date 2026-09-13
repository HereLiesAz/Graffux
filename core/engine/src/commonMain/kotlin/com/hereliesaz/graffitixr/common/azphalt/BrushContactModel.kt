package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val CONTACT_DEG_TO_RAD = 0.017453292f
private const val CONTACT_RAD_TO_DEG = 57.29578f

/**
 * Stroke-local brush mechanics. Motion, pressure, tilt and stylus orientation all enter the model,
 * but pressure/tilt/orientation are treated as provisional intent evidence rather than direct dab
 * parameters. Their couplings are explicit and replaceable so later empirical tuning can improve
 * the intent model without changing the renderer-facing mechanical state contract.
 */
@Serializable
data class BrushContactConfig(
    val enabled: Boolean = false,
    /** 0 = very soft/sluggish, 1 = stiff/quickly tracks a direction change. */
    val stiffness: Float = 0.55f,
    /** How strongly motion speed bends/drags the brush, 0..1. */
    val drag: Float = 0.65f,
    /** Speed (px/ms) that counts as full mechanical drag. */
    val fullBendSpeedPxPerMs: Float = 1.0f,
    /** Resistance to changing established contact direction, 0..1. */
    val hysteresis: Float = 0.35f,
    /** Recovery rate when motion slows/stops, 0..1. */
    val recovery: Float = 0.55f,
    /** Maximum allowed angular lag behind the current mechanical target. */
    val maxLagDeg: Float = 75f,
    /** Maximum center drag behind the hand, in fractions of current brush diameter. */
    val maxDragOffset: Float = 0.18f,
    /** Maximum speed-driven broadening from mechanical splay, as a fraction of width. */
    val dragSplay: Float = 0.12f,
    /** Maximum speed-driven flattening/elongation of the contact footprint, 0..0.95. */
    val dragElongation: Float = 0.2f,
    /** Provisional pressure→compression/splay coupling. Replaceable intent interpretation. */
    val pressureCoupling: Float = 0.35f,
    /** Provisional tilt→side-contact/elongation coupling. */
    val tiltCoupling: Float = 0.35f,
    /** Provisional azimuth→presentation-direction coupling; naturally gated by tilt. */
    val orientationCoupling: Float = 0.35f,
    /** Maximum extra width caused by pressure-driven compression/splay. */
    val pressureSplay: Float = 0.18f,
    /** Maximum extra flattening caused by brush lean. */
    val tiltElongation: Float = 0.22f,
) {
    fun sanitized(): BrushContactConfig = copy(
        stiffness = stiffness.coerceIn(0f, 1f),
        drag = drag.coerceIn(0f, 1f),
        fullBendSpeedPxPerMs = fullBendSpeedPxPerMs.coerceAtLeast(0.01f),
        hysteresis = hysteresis.coerceIn(0f, 1f),
        recovery = recovery.coerceIn(0f, 1f),
        maxLagDeg = maxLagDeg.coerceIn(0f, 180f),
        maxDragOffset = maxDragOffset.coerceIn(0f, 1f),
        dragSplay = dragSplay.coerceIn(0f, 1f),
        dragElongation = dragElongation.coerceIn(0f, 0.95f),
        pressureCoupling = pressureCoupling.coerceIn(0f, 1f),
        tiltCoupling = tiltCoupling.coerceIn(0f, 1f),
        orientationCoupling = orientationCoupling.coerceIn(0f, 1f),
        pressureSplay = pressureSplay.coerceIn(0f, 1f),
        tiltElongation = tiltElongation.coerceIn(0f, 0.95f),
    )

    fun isActive(): Boolean = enabled
}

/** Canonical observation of artist intent signals carried alongside stroke kinematics. */
data class BrushIntentObservation(
    val pressure: Float = 0f,
    val tilt: Float = 0f,
    val orientationDeg: Float = 0f,
)

/** Persistent mechanics carried across dabs within one stroke. */
data class BrushMechanicalState(
    val initialized: Boolean = false,
    /** Current mechanically resolved drag/rake direction in degrees. */
    val dragAngleDeg: Float = 0f,
    /** 0..1 amount of current speed/bend deformation. */
    val bend: Float = 0f,
    /** Stateful contact compression. Pressure informs its target but does not bypass mechanics. */
    val compression: Float = 0f,
    /** Stateful lean/contact-side amount. Tilt informs its target but does not directly reshape. */
    val lean: Float = 0f,
    /** Latest normalized intent observation, retained for later richer solvers/tuft models. */
    val intent: BrushIntentObservation = BrushIntentObservation(),
    val lastUptimeMillis: Long = 0L,
)

/** Renderer-independent instantaneous brush contact resolved from mechanical state. */
data class BrushContactState(
    val widthMultiplier: Float = 1f,
    val tipRatioMultiplier: Float = 1f,
    /** Add this to a heading-based footprint angle to get the mechanically lagged angle. */
    val angleOffsetDeg: Float = 0f,
    /** Contact-center drag in brush-diameter fractions. */
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f,
    val bend: Float = 0f,
    val compression: Float = 0f,
    val lean: Float = 0f,
    val splay: Float = 0f,
)

data class BrushMechanicalStep(
    val state: BrushMechanicalState,
    val contact: BrushContactState,
)

/**
 * Deterministic incremental brush-mechanics model. Kinematics and stylus telemetry are fused into
 * mechanical targets, then stiffness/damping/hysteresis decide how the brush actually responds.
 * This preserves room to improve the interpretation of pressure/tilt/orientation later without
 * changing the persistent state or renderer interface.
 */
object BrushContactModel {
    fun step(
        sample: BrushSample,
        previous: BrushMechanicalState,
        config: BrushContactConfig,
    ): BrushMechanicalStep {
        val cfg = config.sanitized()
        if (!cfg.isActive()) {
            return BrushMechanicalStep(BrushMechanicalState(), BrushContactState())
        }

        val heading = normalizeDegrees(sample.drawingAngleDeg)
        val intent = observeIntent(sample)
        val speedT = (sample.speedPxPerMs / cfg.fullBendSpeedPxPerMs).coerceIn(0f, 1f)

        // Azimuth becomes informative as the stylus leans. Near-vertical orientation is noisy and
        // physically ambiguous, so tilt naturally gates how much it can steer the target rake.
        val orientationWeight = (cfg.orientationCoupling * intent.tilt).coerceIn(0f, 1f)
        val targetAngle = shortestAngleLerp(heading, intent.orientationDeg, orientationWeight)
        val targetBend = (
            speedT * cfg.drag +
                intent.tilt * cfg.tiltCoupling * 0.25f
            ).coerceIn(0f, 1f)
        val targetCompression = (intent.pressure * cfg.pressureCoupling).coerceIn(0f, 1f)
        val targetLean = (intent.tilt * cfg.tiltCoupling).coerceIn(0f, 1f)

        if (!previous.initialized) {
            val initial = BrushMechanicalState(
                initialized = true,
                dragAngleDeg = targetAngle,
                bend = targetBend,
                compression = targetCompression,
                lean = targetLean,
                intent = intent,
                lastUptimeMillis = sample.uptimeMillis,
            )
            return BrushMechanicalStep(initial, contactFor(initial, heading, cfg))
        }

        val dtMs = (sample.uptimeMillis - previous.lastUptimeMillis)
            .coerceIn(1L, 100L)
            .toFloat()
        val turn = wrapSignedDegrees(targetAngle - previous.dragAngleDeg)
        val turnT = (abs(turn) / 180f).coerceIn(0f, 1f)

        // Stiff brushes track quickly; soft brushes take longer. Strong hysteresis slows response
        // specifically on large direction changes, preserving established rake/drag direction.
        val trackingTauMs = lerp(240f, 24f, cfg.stiffness)
        val rawTracking = 1f - exp((-dtMs / trackingTauMs).toDouble()).toFloat()
        val hysteresisFactor = (1f - cfg.hysteresis * turnT * 0.9f).coerceIn(0.05f, 1f)
        var dragAngle = previous.dragAngleDeg + turn * rawTracking * hysteresisFactor

        var lag = wrapSignedDegrees(dragAngle - targetAngle)
        lag = lag.coerceIn(-cfg.maxLagDeg, cfg.maxLagDeg)
        dragAngle = normalizeDegrees(targetAngle + lag)

        val bendTauMs = if (speedT > 0.02f || targetLean > 0.02f) {
            lerp(180f, 28f, cfg.stiffness)
        } else {
            lerp(360f, 36f, cfg.recovery)
        }
        val bendResponse = response(dtMs, bendTauMs)
        val contactTauMs = lerp(150f, 24f, cfg.stiffness)
        val contactResponse = response(dtMs, contactTauMs)

        val bend = approach(previous.bend, targetBend, bendResponse)
        val compression = approach(previous.compression, targetCompression, contactResponse)
        val lean = approach(previous.lean, targetLean, contactResponse)

        val next = BrushMechanicalState(
            initialized = true,
            dragAngleDeg = dragAngle,
            bend = bend,
            compression = compression,
            lean = lean,
            intent = intent,
            lastUptimeMillis = sample.uptimeMillis,
        )
        return BrushMechanicalStep(next, contactFor(next, heading, cfg))
    }

    private fun observeIntent(sample: BrushSample): BrushIntentObservation {
        val pressure = sample.pressure.coerceIn(0f, 1f)
        val tilt = (sample.tiltRadians / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
        val orientation = normalizeDegrees(sample.orientationRadians * CONTACT_RAD_TO_DEG)
        return BrushIntentObservation(pressure, tilt, orientation)
    }

    private fun contactFor(
        state: BrushMechanicalState,
        movementHeadingDeg: Float,
        cfg: BrushContactConfig,
    ): BrushContactState {
        val bend = state.bend.coerceIn(0f, 1f)
        val compression = state.compression.coerceIn(0f, 1f)
        val lean = state.lean.coerceIn(0f, 1f)
        val lagOffset = wrapSignedDegrees(state.dragAngleDeg - movementHeadingDeg)

        val motionSplay = cfg.dragSplay * bend
        val pressureSplay = cfg.pressureSplay * compression
        val splay = (motionSplay + pressureSplay).coerceAtLeast(0f)
        val width = 1f + splay
        val flattening = cfg.dragElongation * bend + cfg.tiltElongation * lean
        val tipRatio = (1f - flattening).coerceIn(0.05f, 1f)

        // Contact trails opposite the mechanically resolved rake direction.
        val dragDistance = cfg.maxDragOffset * bend
        val dragRad = state.dragAngleDeg * CONTACT_DEG_TO_RAD
        val offsetX = -cos(dragRad) * dragDistance
        val offsetY = -sin(dragRad) * dragDistance

        return BrushContactState(
            widthMultiplier = width,
            tipRatioMultiplier = tipRatio,
            angleOffsetDeg = lagOffset,
            offsetXFraction = offsetX,
            offsetYFraction = offsetY,
            bend = bend,
            compression = compression,
            lean = lean,
            splay = splay,
        )
    }

    private fun response(dtMs: Float, tauMs: Float): Float =
        1f - exp((-dtMs / tauMs.coerceAtLeast(1f)).toDouble()).toFloat()

    private fun approach(current: Float, target: Float, response: Float): Float =
        (current + (target - current) * response).coerceIn(0f, 1f)

    private fun shortestAngleLerp(fromDeg: Float, toDeg: Float, t: Float): Float =
        normalizeDegrees(fromDeg + wrapSignedDegrees(toDeg - fromDeg) * t.coerceIn(0f, 1f))

    private fun normalizeDegrees(value: Float): Float {
        var v = value % 360f
        if (v < 0f) v += 360f
        return v
    }

    private fun wrapSignedDegrees(value: Float): Float {
        var v = value % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}
