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
 * Stroke-local brush mechanics. Motion, pressure, tilt, stylus orientation and device tip pose all
 * enter the model, but expressive telemetry is treated as intent evidence rather than direct
 * renderer parameters. Couplings remain explicit/replaceable so richer physics can reinterpret
 * them without changing the renderer-facing contact contract.
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
    /** Provisional pressure/contact→compression/splay coupling. Replaceable intent interpretation. */
    val pressureCoupling: Float = 0.35f,
    /** Provisional tilt→side-contact/elongation coupling. */
    val tiltCoupling: Float = 0.35f,
    /** Provisional azimuth→presentation-direction coupling; naturally gated by tilt. */
    val orientationCoupling: Float = 0.35f,
    /** Maximum extra width caused by compression/splay. */
    val pressureSplay: Float = 0.18f,
    /** Maximum extra flattening caused by brush lean. */
    val tiltElongation: Float = 0.22f,
    /** Optional stable coarse bristle-bundle topology. Disabled by default for exact compatibility. */
    val tufts: BrushTuftConfig = BrushTuftConfig(),
    /** Optional phone/tablet attitude + manual 3D tip-pose controls. */
    val devicePresentation: BrushDevicePresentationConfig = BrushDevicePresentationConfig(),
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
        tufts = tufts.sanitized(),
        devicePresentation = devicePresentation.sanitized(),
    )

    fun isActive(): Boolean = enabled
}

/** Canonical observation of artist-intent evidence carried alongside stroke kinematics. */
data class BrushIntentObservation(
    val profile: BrushTelemetryProfile = BrushTelemetryProfile.LEGACY,
    val pressure: Float = 0f,
    val pressureConfidence: Float = 0f,
    val tilt: Float = 0f,
    val tiltConfidence: Float = 0f,
    val orientationDeg: Float = 0f,
    val orientationConfidence: Float = 0f,
    val contactMajor: Float = 0f,
    val contactMinor: Float = 0f,
    val contactConfidence: Float = 0f,
    val contactPhase: BrushContactPhase = BrushContactPhase.CONTACT,
    /** Phone/tablet attitude remains independent from pointer/stylus orientation evidence. */
    val deviceAttitude: BrushDeviceAttitude = BrushDeviceAttitude(),
)

/** Persistent mechanics carried across dabs within one stroke. */
data class BrushMechanicalState(
    val initialized: Boolean = false,
    /** Current mechanically resolved drag/rake direction in degrees. */
    val dragAngleDeg: Float = 0f,
    /** 0..1 amount of current speed/bend deformation. */
    val bend: Float = 0f,
    /** Stateful contact compression. Telemetry informs its target but does not bypass mechanics. */
    val compression: Float = 0f,
    /** Stateful lean/contact-side amount. Stylus tilt informs its target but does not directly reshape. */
    val lean: Float = 0f,
    /** Stable per-bundle deformation memory. Empty when coarse tuft mechanics are disabled. */
    val tufts: List<BrushTuftMechanicalState> = emptyList(),
    /** Latest intent evidence, retained for later richer solvers/tuft models. */
    val intent: BrushIntentObservation = BrushIntentObservation(),
    val lastUptimeMillis: Long = 0L,
    /** Independent 3D tip pose derived from phone/tablet attitude and user calibration. */
    val presentation: BrushDevicePresentationState = BrushDevicePresentationState(),
)

/** Renderer-independent instantaneous brush contact resolved from mechanical state. */
data class BrushContactState(
    val widthMultiplier: Float = 1f,
    val tipRatioMultiplier: Float = 1f,
    /** Add this to a heading-based footprint angle to get the mechanically lagged drag angle. */
    val angleOffsetDeg: Float = 0f,
    /** Contact-center drag in brush-diameter fractions. */
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f,
    val bend: Float = 0f,
    val compression: Float = 0f,
    val lean: Float = 0f,
    val splay: Float = 0f,
    /** Stable bundle contacts relative to this global contact center. Empty when topology is off. */
    val tufts: List<BrushTuftContact> = emptyList(),
    /** Screen-space shaft lean used to decide which part of an angular tip contacts first. */
    val tipLeanX: Float = 0f,
    val tipLeanY: Float = 0f,
    /** Axial twist of the tip/nib around its own shaft; unrelated to canvas rotation. */
    val tipTwistDeg: Float = 0f,
)

data class BrushMechanicalStep(
    val state: BrushMechanicalState,
    val contact: BrushContactState,
)

/**
 * Deterministic incremental brush-mechanics model. Kinematics and device-specific telemetry are
 * fused into mechanical targets, then stiffness/damping/hysteresis decide how the brush actually
 * responds. Signal confidence is part of the calculation, so missing/basic/finger telemetry does
 * not receive the same authority as a high-quality stylus.
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
        val presentation = BrushDevicePresentationModel.resolve(
            attitude = intent.deviceAttitude,
            previous = previous.presentation,
            config = cfg.devicePresentation,
        )
        val speedT = (sample.speedPxPerMs / cfg.fullBendSpeedPxPerMs).coerceIn(0f, 1f)
        val tiltEvidence = (intent.tilt * intent.tiltConfidence).coerceIn(0f, 1f)

        val orientationWeight = (
            cfg.orientationCoupling * tiltEvidence * intent.orientationConfidence
            ).coerceIn(0f, 1f)
        val targetAngle = shortestAngleLerp(heading, intent.orientationDeg, orientationWeight)
        val targetBend = (
            speedT * cfg.drag + tiltEvidence * cfg.tiltCoupling * 0.25f
            ).coerceIn(0f, 1f)

        val pressureEvidence = (intent.pressure * intent.pressureConfidence).coerceIn(0f, 1f)
        val contactEvidence = (intent.contactMajor * intent.contactConfidence).coerceIn(0f, 1f)
        val compressionEvidence = maxOf(pressureEvidence, contactEvidence)
        val targetCompression = (compressionEvidence * cfg.pressureCoupling).coerceIn(0f, 1f)
        val targetLean = (tiltEvidence * cfg.tiltCoupling).coerceIn(0f, 1f)

        if (!previous.initialized) {
            val global = BrushMechanicalState(
                initialized = true,
                dragAngleDeg = targetAngle,
                bend = targetBend,
                compression = targetCompression,
                lean = targetLean,
                intent = intent,
                lastUptimeMillis = sample.uptimeMillis,
                presentation = presentation,
            )
            return withTuftMechanics(
                global = global,
                previousTufts = emptyList(),
                movementHeadingDeg = heading,
                cfg = cfg,
                dtMs = 0f,
            )
        }

        val dtMs = (sample.uptimeMillis - previous.lastUptimeMillis)
            .coerceIn(1L, 100L)
            .toFloat()
        val turn = wrapSignedDegrees(targetAngle - previous.dragAngleDeg)
        val turnT = (abs(turn) / 180f).coerceIn(0f, 1f)

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

        val global = BrushMechanicalState(
            initialized = true,
            dragAngleDeg = dragAngle,
            bend = bend,
            compression = compression,
            lean = lean,
            intent = intent,
            lastUptimeMillis = sample.uptimeMillis,
            presentation = presentation,
        )
        return withTuftMechanics(
            global = global,
            previousTufts = previous.tufts,
            movementHeadingDeg = heading,
            cfg = cfg,
            dtMs = dtMs,
        )
    }

    private fun withTuftMechanics(
        global: BrushMechanicalState,
        previousTufts: List<BrushTuftMechanicalState>,
        movementHeadingDeg: Float,
        cfg: BrushContactConfig,
        dtMs: Float,
    ): BrushMechanicalStep {
        val base = baseContactFor(global, movementHeadingDeg, cfg)
        val tuftStep = BrushTuftTopology.step(
            previous = previousTufts,
            state = global,
            contact = base,
            config = cfg.tufts,
            dtMs = dtMs,
        )
        return BrushMechanicalStep(
            state = global.copy(tufts = tuftStep.states),
            contact = base.copy(tufts = tuftStep.contacts),
        )
    }

    private fun observeIntent(sample: BrushSample): BrushIntentObservation {
        val telemetry = sample.intentTelemetry()
        val tilt = (telemetry.tiltRadians / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
        val orientation = normalizeDegrees(telemetry.orientationRadians * CONTACT_RAD_TO_DEG)
        val contactMajor = (telemetry.contactMajorPx / FINGER_CONTACT_FULL_PRESSURE_PX).coerceIn(0f, 1f)
        val contactMinor = (telemetry.contactMinorPx / FINGER_CONTACT_FULL_PRESSURE_PX).coerceIn(0f, 1f)
        return BrushIntentObservation(
            profile = telemetry.profile,
            pressure = telemetry.pressure,
            pressureConfidence = telemetry.pressureConfidence,
            tilt = tilt,
            tiltConfidence = telemetry.tiltConfidence,
            orientationDeg = orientation,
            orientationConfidence = telemetry.orientationConfidence,
            contactMajor = contactMajor,
            contactMinor = contactMinor,
            contactConfidence = telemetry.contactConfidence,
            contactPhase = telemetry.contactPhase,
            deviceAttitude = telemetry.deviceAttitude,
        )
    }

    private fun baseContactFor(
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
            tipLeanX = state.presentation.leanX,
            tipLeanY = state.presentation.leanY,
            tipTwistDeg = state.presentation.twistDeg,
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
