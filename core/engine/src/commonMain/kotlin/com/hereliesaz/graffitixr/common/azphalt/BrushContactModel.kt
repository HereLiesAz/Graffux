package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val CONTACT_DEG_TO_RAD = 0.017453292f

/**
 * Stroke-local brush mechanics driven by motion first. Pressure / tilt / stylus orientation are
 * deliberately NOT consumed here yet: those become intent inputs only after the intrinsic brush
 * physics is stable. Defaults are identity/off for compatibility.
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
    /** Maximum allowed angular lag behind the current movement direction. */
    val maxLagDeg: Float = 75f,
    /** Maximum center drag behind the hand, in fractions of current brush diameter. */
    val maxDragOffset: Float = 0.18f,
    /** Maximum speed-driven broadening from mechanical splay, as a fraction of width. */
    val dragSplay: Float = 0.12f,
    /** Maximum speed-driven flattening/elongation of the contact footprint, 0..0.95. */
    val dragElongation: Float = 0.2f,
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
    )

    fun isActive(): Boolean = enabled && (
        drag > 0f || maxDragOffset > 0f || dragSplay > 0f || dragElongation > 0f || hysteresis > 0f
        )
}

/** Persistent mechanics carried across dabs within one stroke. */
data class BrushMechanicalState(
    val initialized: Boolean = false,
    /** Current mechanically resolved drag/rake direction in degrees. */
    val dragAngleDeg: Float = 0f,
    /** 0..1 amount of current speed/bend deformation. */
    val bend: Float = 0f,
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
    val splay: Float = 0f,
)

data class BrushMechanicalStep(
    val state: BrushMechanicalState,
    val contact: BrushContactState,
)

/**
 * Deterministic incremental brush-mechanics model. It uses only stroke kinematics: movement
 * heading, speed and elapsed time. Pressure/tilt/orientation will be layered on later as intent
 * targets that steer this same state rather than bypassing it.
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
        val speedT = (sample.speedPxPerMs / cfg.fullBendSpeedPxPerMs).coerceIn(0f, 1f)
        val targetBend = speedT * cfg.drag

        if (!previous.initialized) {
            val initial = BrushMechanicalState(
                initialized = true,
                dragAngleDeg = heading,
                bend = targetBend,
                lastUptimeMillis = sample.uptimeMillis,
            )
            return BrushMechanicalStep(initial, contactFor(initial, heading, cfg))
        }

        val dtMs = (sample.uptimeMillis - previous.lastUptimeMillis)
            .coerceIn(1L, 100L)
            .toFloat()
        val turn = wrapSignedDegrees(heading - previous.dragAngleDeg)
        val turnT = (abs(turn) / 180f).coerceIn(0f, 1f)

        // Stiff brushes track quickly; soft brushes take longer. Strong hysteresis slows response
        // specifically on large direction changes, preserving established rake/drag direction.
        val trackingTauMs = lerp(240f, 24f, cfg.stiffness)
        val rawTracking = 1f - exp((-dtMs / trackingTauMs).toDouble()).toFloat()
        val hysteresisFactor = (1f - cfg.hysteresis * turnT * 0.9f).coerceIn(0.05f, 1f)
        var dragAngle = previous.dragAngleDeg + turn * rawTracking * hysteresisFactor

        // Never let a soft brush accumulate physically absurd multi-turn lag. Clamp relative to
        // the current path direction while keeping the sign of the established lag.
        var lag = wrapSignedDegrees(dragAngle - heading)
        lag = lag.coerceIn(-cfg.maxLagDeg, cfg.maxLagDeg)
        dragAngle = normalizeDegrees(heading + lag)

        val bendTauMs = if (speedT > 0.02f) {
            lerp(180f, 28f, cfg.stiffness)
        } else {
            // At rest, recovery is separately configurable from directional stiffness.
            lerp(360f, 36f, cfg.recovery)
        }
        val bendResponse = 1f - exp((-dtMs / bendTauMs).toDouble()).toFloat()
        val bend = (previous.bend + (targetBend - previous.bend) * bendResponse).coerceIn(0f, 1f)

        val next = BrushMechanicalState(
            initialized = true,
            dragAngleDeg = dragAngle,
            bend = bend,
            lastUptimeMillis = sample.uptimeMillis,
        )
        return BrushMechanicalStep(next, contactFor(next, heading, cfg))
    }

    private fun contactFor(
        state: BrushMechanicalState,
        movementHeadingDeg: Float,
        cfg: BrushContactConfig,
    ): BrushContactState {
        val bend = state.bend.coerceIn(0f, 1f)
        val lagOffset = wrapSignedDegrees(state.dragAngleDeg - movementHeadingDeg)
        val splay = cfg.dragSplay * bend
        val width = 1f + splay
        val tipRatio = (1f - cfg.dragElongation * bend).coerceIn(0.05f, 1f)

        // Contact trails opposite the mechanically resolved drag direction.
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
            splay = splay,
        )
    }

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
