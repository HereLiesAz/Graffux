package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val TUFT_DEG_TO_RAD = 0.017453292f

/**
 * Coarse physical brush families. CUSTOM is the exact historical topology and remains the default
 * so existing serialized brushes and replay geometry do not change merely by loading newer code.
 */
@Serializable
enum class BrushMorphology {
    @SerialName("custom") CUSTOM,
    @SerialName("round") ROUND,
    @SerialName("flat") FLAT,
    @SerialName("filbert") FILBERT,
    @SerialName("rigger") RIGGER,
    @SerialName("fan") FAN,
    @SerialName("rake") RAKE,
}

@Serializable
data class BrushTuftConfig(
    val enabled: Boolean = false,
    val count: Int = 5,
    val rootSpan: Float = 0.72f,
    val cohesion: Float = 0.72f,
    val splayResponse: Float = 0.8f,
    val bendDifferential: Float = 0.12f,
    val tuftWidthScale: Float = 1f,
    val deformationResponse: Float = 0.62f,
    val hysteresis: Float = 0.45f,
    val recovery: Float = 0.68f,
    val maxLagDeg: Float = 22f,
    val splitThreshold: Float = 0.46f,
    val rejoinThreshold: Float = 0.28f,
    val splitResponse: Float = 0.7f,
    val splitSeparation: Float = 0.16f,
    val splitBendWeight: Float = 0.45f,
    /** Extra footprint width on explicit touchdown before the tuft settles into drag. */
    val touchdownCompression: Float = 0.22f,
    /** Maximum time a non-dragging touchdown remains in the compressed stab state. */
    val touchdownHoldMs: Float = 48f,
    /** Global bend at which a stab is considered to have transitioned into drag. */
    val stabToDragBend: Float = 0.22f,
    /** Response speed for explicit lift-off collapse. */
    val liftReleaseResponse: Float = 0.8f,
    /** Minimum tuft radius scale retained at full lift-off. */
    val liftRadiusFloor: Float = 0.3f,
    /** Global rake-direction change that counts as a reversal impulse. */
    val reversalThresholdDeg: Float = 105f,
    /** How strongly an established split is retained through a reversal. */
    val reversalPersistence: Float = 0.75f,
    /** Temporary angular lag allowed while a reversal impulse is unloading. */
    val reversalMaxLagDeg: Float = 140f,
    val emitTuftDabs: Boolean = false,
    /** Stable internal tuft arrangement/deformation family. CUSTOM preserves legacy mechanics. */
    val morphology: BrushMorphology = BrushMorphology.CUSTOM,
) {
    fun sanitized(): BrushTuftConfig {
        val split = splitThreshold.coerceIn(0.01f, 1f)
        return copy(
            count = count.coerceIn(1, 16),
            rootSpan = rootSpan.coerceIn(0f, 1.5f),
            cohesion = cohesion.coerceIn(0f, 1f),
            splayResponse = splayResponse.coerceIn(0f, 2f),
            bendDifferential = bendDifferential.coerceIn(0f, 0.5f),
            tuftWidthScale = tuftWidthScale.coerceIn(0.1f, 2f),
            deformationResponse = deformationResponse.coerceIn(0f, 1f),
            hysteresis = hysteresis.coerceIn(0f, 1f),
            recovery = recovery.coerceIn(0f, 1f),
            maxLagDeg = maxLagDeg.coerceIn(0f, 90f),
            splitThreshold = split,
            rejoinThreshold = rejoinThreshold.coerceIn(0f, split),
            splitResponse = splitResponse.coerceIn(0f, 1f),
            splitSeparation = splitSeparation.coerceIn(0f, 0.75f),
            splitBendWeight = splitBendWeight.coerceIn(0f, 1f),
            touchdownCompression = touchdownCompression.coerceIn(0f, 1f),
            touchdownHoldMs = touchdownHoldMs.coerceIn(0f, 500f),
            stabToDragBend = stabToDragBend.coerceIn(0f, 1f),
            liftReleaseResponse = liftReleaseResponse.coerceIn(0f, 1f),
            liftRadiusFloor = liftRadiusFloor.coerceIn(0.05f, 1f),
            reversalThresholdDeg = reversalThresholdDeg.coerceIn(45f, 180f),
            reversalPersistence = reversalPersistence.coerceIn(0f, 1f),
            reversalMaxLagDeg = reversalMaxLagDeg.coerceIn(maxLagDeg.coerceIn(0f, 90f), 180f),
        )
    }

    fun isActive(): Boolean = enabled && count > 1
    fun emitsDabs(): Boolean = isActive() && emitTuftDabs
}

data class BrushTuftIdentity(
    val id: Int,
    val rootLateralFraction: Float,
    val stiffnessScale: Float,
    /** Ferrule-local forward/back root placement in brush-diameter fractions. */
    val rootLongitudinalFraction: Float = 0f,
    /** Per-bundle width before touchdown/lift scaling. */
    val widthScale: Float = 1f,
    /** Stable local presentation angle; fan tufts use this to radiate rather than form one row. */
    val angleBiasDeg: Float = 0f,
    /** Local multiplier on global cohesion. */
    val cohesionScale: Float = 1f,
    /** Local multiplier on splay response. */
    val splayScale: Float = 1f,
    /** Local multiplier on bend loading. */
    val bendScale: Float = 1f,
    /** Local multiplier on split/breakaway loading. */
    val splitScale: Float = 1f,
    /** Local multiplier on differential trailing displacement. */
    val trailScale: Float = 1f,
    /** Stable normalized distance from the topology center, independent of morphology span. */
    val edgeWeight: Float = 0f,
)

data class BrushTuftMechanicalState(
    val id: Int,
    val initialized: Boolean = false,
    val dragAngleDeg: Float = 0f,
    val bend: Float = 0f,
    val separationFraction: Float = 0f,
    val trailingFraction: Float = 0f,
    val splitDrive: Float = 0f,
    val splitAmount: Float = 0f,
    val splitLatched: Boolean = false,
    /** Stroke-local time since this stable tuft first contacted the surface. */
    val contactAgeMs: Float = 0f,
    /** 0..1 compressed stab/touchdown state. */
    val touchdownAmount: Float = 0f,
    /** 0..1 explicit lift-off release state. */
    val liftAmount: Float = 0f,
    /** Short-lived reversal impulse used to retain split and then snap direction. */
    val reversalImpulse: Float = 0f,
    /** Previous global rake direction, retained to detect true reversals. */
    val lastGlobalDragAngleDeg: Float = 0f,
)

data class BrushTuftContact(
    val id: Int,
    val rootLateralFraction: Float,
    val offsetXFraction: Float,
    val offsetYFraction: Float,
    val radiusScale: Float,
    val alphaScale: Float,
    val angleOffsetDeg: Float,
    val stiffnessScale: Float,
    val splitAmount: Float = 0f,
    val touchdownAmount: Float = 0f,
    val liftAmount: Float = 0f,
    val reversalImpulse: Float = 0f,
    /** Diagnostics/future morphology tools; appended to preserve positional constructor callers. */
    val rootLongitudinalFraction: Float = 0f,
    val morphology: BrushMorphology = BrushMorphology.CUSTOM,
)

data class BrushTuftMechanicalStep(
    val states: List<BrushTuftMechanicalState>,
    val contacts: List<BrushTuftContact>,
)

object BrushTuftTopology {
    /**
     * Stable deterministic roots for one physical brush family. No RNG participates in morphology.
     * CUSTOM intentionally reproduces the pre-morphology one-dimensional layout exactly.
     */
    fun layout(config: BrushTuftConfig): List<BrushTuftIdentity> {
        val cfg = config.sanitized()
        if (!cfg.isActive()) return emptyList()
        val count = cfg.count
        val halfSpan = cfg.rootSpan * 0.5f
        return List(count) { index ->
            val normalized = if (count == 1) 0f else index.toFloat() / (count - 1).toFloat()
            val signedT = normalized * 2f - 1f
            val edgeT = if (halfSpan > 0f) abs(signedT).coerceIn(0f, 1f) else 0f
            morphologyIdentity(cfg, index, signedT, edgeT, halfSpan)
        }
    }

    private fun morphologyIdentity(
        cfg: BrushTuftConfig,
        index: Int,
        signedT: Float,
        edgeT: Float,
        halfSpan: Float,
    ): BrushTuftIdentity {
        val edgeSq = edgeT * edgeT
        return when (cfg.morphology) {
            BrushMorphology.CUSTOM -> BrushTuftIdentity(
                id = index,
                rootLateralFraction = signedT * halfSpan,
                stiffnessScale = 1f - edgeT * 0.2f,
                edgeWeight = edgeT,
            )

            BrushMorphology.ROUND -> BrushTuftIdentity(
                id = index,
                rootLateralFraction = signedT * halfSpan * 0.88f,
                stiffnessScale = 1f - edgeT * 0.16f,
                rootLongitudinalFraction = (1f - edgeSq) * cfg.rootSpan * 0.055f,
                widthScale = 0.82f + (1f - edgeSq) * 0.18f,
                cohesionScale = 1.08f,
                splayScale = 0.82f + edgeT * 0.18f,
                bendScale = 0.94f + edgeT * 0.08f,
                splitScale = 0.78f + edgeT * 0.2f,
                trailScale = 0.88f + edgeT * 0.12f,
                edgeWeight = edgeT,
            )

            BrushMorphology.FLAT -> BrushTuftIdentity(
                id = index,
                rootLateralFraction = signedT * halfSpan,
                stiffnessScale = 0.98f - edgeT * 0.06f,
                widthScale = 1f,
                cohesionScale = 1.12f,
                splayScale = 0.72f + edgeT * 0.12f,
                bendScale = 0.9f,
                splitScale = 0.7f + edgeT * 0.12f,
                trailScale = 0.82f,
                edgeWeight = edgeT,
            )

            BrushMorphology.FILBERT -> BrushTuftIdentity(
                id = index,
                rootLateralFraction = signedT * halfSpan * 0.96f,
                stiffnessScale = 1f - edgeT * 0.22f,
                rootLongitudinalFraction = (1f - edgeSq) * cfg.rootSpan * 0.085f,
                widthScale = 0.68f + (1f - edgeSq) * 0.32f,
                cohesionScale = 0.98f,
                splayScale = 0.92f + edgeT * 0.22f,
                bendScale = 0.96f + edgeT * 0.16f,
                splitScale = 0.9f + edgeT * 0.28f,
                trailScale = 0.92f + edgeT * 0.24f,
                edgeWeight = edgeT,
            )

            BrushMorphology.RIGGER -> BrushTuftIdentity(
                id = index,
                rootLateralFraction = signedT * halfSpan * 0.3f,
                stiffnessScale = 0.84f - edgeT * 0.12f,
                rootLongitudinalFraction = (1f - edgeSq) * cfg.rootSpan * 0.14f,
                widthScale = 0.52f + (1f - edgeT) * 0.18f,
                cohesionScale = 1.06f,
                splayScale = 0.48f + edgeT * 0.22f,
                bendScale = 1.22f + edgeT * 0.08f,
                splitScale = 0.62f + edgeT * 0.12f,
                trailScale = 1.35f + edgeT * 0.15f,
                edgeWeight = edgeT,
            )

            BrushMorphology.FAN -> BrushTuftIdentity(
                id = index,
                rootLateralFraction = signedT * halfSpan * 1.12f,
                stiffnessScale = 0.88f - edgeT * 0.16f,
                rootLongitudinalFraction = -edgeSq * cfg.rootSpan * 0.035f,
                widthScale = 0.66f + (1f - edgeT) * 0.2f,
                angleBiasDeg = signedT * 22f,
                cohesionScale = 0.72f,
                splayScale = 1.2f + edgeT * 0.18f,
                bendScale = 1.02f + edgeT * 0.12f,
                splitScale = 1.18f + edgeT * 0.22f,
                trailScale = 1.05f + edgeT * 0.15f,
                edgeWeight = edgeT,
            )

            BrushMorphology.RAKE -> BrushTuftIdentity(
                id = index,
                rootLateralFraction = signedT * halfSpan,
                stiffnessScale = 0.94f - edgeT * 0.04f,
                rootLongitudinalFraction = if (index % 2 == 0) {
                    cfg.rootSpan * 0.025f
                } else {
                    -cfg.rootSpan * 0.025f
                },
                widthScale = 0.54f,
                cohesionScale = 0.62f,
                splayScale = 0.94f + edgeT * 0.12f,
                bendScale = 0.94f,
                splitScale = 1.32f + edgeT * 0.16f,
                trailScale = 0.9f,
                edgeWeight = edgeT,
            )
        }
    }

    /** Stateless cohesive reference geometry. Phase transitions require stroke history. */
    fun resolve(
        state: BrushMechanicalState,
        contact: BrushContactState,
        config: BrushTuftConfig,
    ): List<BrushTuftContact> {
        val cfg = config.sanitized()
        return layout(cfg).map { tuft ->
            val target = targets(tuft, contact, cfg)
            contactFor(
                identity = tuft,
                dragAngleDeg = state.dragAngleDeg,
                globalDragAngleDeg = state.dragAngleDeg,
                lateralFraction = tuft.rootLateralFraction + target.cohesiveSeparationFraction,
                trailingFraction = target.trailingFraction,
                splitAmount = 0f,
                touchdownAmount = 0f,
                liftAmount = 0f,
                reversalImpulse = 0f,
                cfg = cfg,
            )
        }
    }

    fun step(
        previous: List<BrushTuftMechanicalState>,
        state: BrushMechanicalState,
        contact: BrushContactState,
        config: BrushTuftConfig,
        dtMs: Float,
    ): BrushTuftMechanicalStep {
        val cfg = config.sanitized()
        val identities = layout(cfg)
        if (identities.isEmpty()) return BrushTuftMechanicalStep(emptyList(), emptyList())

        val previousById = previous.associateBy { it.id }
        val nextStates = ArrayList<BrushTuftMechanicalState>(identities.size)
        val contacts = ArrayList<BrushTuftContact>(identities.size)

        identities.forEach { identity ->
            val target = targets(identity, contact, cfg)
            val old = previousById[identity.id]
            val next = if (old == null || !old.initialized || dtMs <= 0f) {
                val touchdown = if (state.intent.contactPhase == BrushContactPhase.TOUCHDOWN) 1f else 0f
                val lift = if (state.intent.contactPhase == BrushContactPhase.LIFT_OFF) 1f else 0f
                BrushTuftMechanicalState(
                    id = identity.id,
                    initialized = true,
                    dragAngleDeg = normalizeDegrees(state.dragAngleDeg),
                    bend = if (lift > 0f) 0f else target.bend,
                    separationFraction = if (lift > 0f) 0f else target.cohesiveSeparationFraction,
                    trailingFraction = 0f,
                    splitDrive = 0f,
                    splitAmount = 0f,
                    splitLatched = false,
                    contactAgeMs = 0f,
                    touchdownAmount = touchdown,
                    liftAmount = lift,
                    reversalImpulse = 0f,
                    lastGlobalDragAngleDeg = state.dragAngleDeg,
                )
            } else {
                evolve(old, identity, state, target, cfg, dtMs)
            }
            nextStates += next
            contacts += contactFor(
                identity = identity,
                dragAngleDeg = next.dragAngleDeg,
                globalDragAngleDeg = state.dragAngleDeg,
                lateralFraction = identity.rootLateralFraction + next.separationFraction,
                trailingFraction = next.trailingFraction,
                splitAmount = next.splitAmount,
                touchdownAmount = next.touchdownAmount,
                liftAmount = next.liftAmount,
                reversalImpulse = next.reversalImpulse,
                cfg = cfg,
            )
        }
        return BrushTuftMechanicalStep(nextStates, contacts)
    }

    private data class TuftTargets(
        val bend: Float,
        val cohesiveSeparationFraction: Float,
        val trailingFraction: Float,
        val splitLoad: Float,
        val edgeT: Float,
    )

    private fun targets(identity: BrushTuftIdentity, contact: BrushContactState, cfg: BrushTuftConfig): TuftTargets {
        val edgeT = identity.edgeWeight.coerceIn(0f, 1f)
        val localCohesion = (cfg.cohesion * identity.cohesionScale).coerceIn(0f, 1f)
        val freeMotion = 1f - localCohesion
        val splayGain = 1f + contact.splay * cfg.splayResponse * identity.splayScale *
            (0.35f + freeMotion * 0.65f)
        val lateral = identity.rootLateralFraction * splayGain
        val cohesiveSeparation = lateral - identity.rootLateralFraction
        val softness = 1f - identity.stiffnessScale.coerceIn(0f, 1f)
        val bend = (
            contact.bend * identity.bendScale * (1f + softness * 0.2f)
            ).coerceIn(0f, 1f)
        val trailing = bend * cfg.bendDifferential * identity.trailScale * softness *
            (0.25f + freeMotion * 0.75f)
        val rawLoad = (
            contact.splay * (1f - cfg.splitBendWeight) + contact.bend * cfg.splitBendWeight
            ).coerceIn(0f, 1f)
        val edgeExposure = if (edgeT <= 1e-4f) 0f else 0.35f + edgeT * 0.65f
        val cohesionResistance = 1f - localCohesion * 0.45f
        return TuftTargets(
            bend = bend,
            cohesiveSeparationFraction = cohesiveSeparation,
            trailingFraction = trailing,
            splitLoad = (rawLoad * edgeExposure * cohesionResistance * identity.splitScale).coerceIn(0f, 1f),
            edgeT = edgeT,
        )
    }

    private fun evolve(
        previous: BrushTuftMechanicalState,
        identity: BrushTuftIdentity,
        globalState: BrushMechanicalState,
        target: TuftTargets,
        cfg: BrushTuftConfig,
        dtMs: Float,
    ): BrushTuftMechanicalState {
        val stiffness = identity.stiffnessScale.coerceIn(0.1f, 1f)
        val responseStrength = (cfg.deformationResponse * stiffness).coerceIn(0f, 1f)
        val deformationTauMs = lerp(260f, 24f, responseStrength)
        val recoveryTauMs = lerp(420f, 42f, cfg.recovery * stiffness)
        val ageMs = previous.contactAgeMs + dtMs
        val phase = globalState.intent.contactPhase

        val touchdownTarget = if (
            phase != BrushContactPhase.LIFT_OFF &&
            previous.touchdownAmount > 0f &&
            ageMs <= cfg.touchdownHoldMs &&
            target.bend < cfg.stabToDragBend
        ) 1f else 0f
        val touchdown = approach(
            previous.touchdownAmount,
            touchdownTarget,
            response(dtMs, lerp(160f, 22f, cfg.deformationResponse)),
        )

        val liftTarget = if (phase == BrushContactPhase.LIFT_OFF) 1f else 0f
        val liftTauMs = lerp(220f, 20f, cfg.liftReleaseResponse * stiffness)
        val lift = approach(previous.liftAmount, liftTarget, response(dtMs, liftTauMs))

        val globalTurn = abs(wrapSignedDegrees(globalState.dragAngleDeg - previous.lastGlobalDragAngleDeg))
        val reversalDetected = phase != BrushContactPhase.LIFT_OFF &&
            globalTurn >= cfg.reversalThresholdDeg && previous.bend > 0.05f
        val reversal = if (reversalDetected) {
            1f
        } else {
            approach(previous.reversalImpulse, 0f, response(dtMs, recoveryTauMs))
        }

        val turn = wrapSignedDegrees(globalState.dragAngleDeg - previous.dragAngleDeg)
        val turnT = (abs(turn) / 180f).coerceIn(0f, 1f)
        val angleResponse = response(dtMs, deformationTauMs) *
            (1f - cfg.hysteresis * turnT * 0.9f).coerceIn(0.05f, 1f)
        var tuftAngle = previous.dragAngleDeg + turn * angleResponse
        val allowedLag = lerp(cfg.maxLagDeg, cfg.reversalMaxLagDeg, reversal)
        var lag = wrapSignedDegrees(tuftAngle - globalState.dragAngleDeg)
        lag = lag.coerceIn(-allowedLag, allowedLag)
        tuftAngle = normalizeDegrees(globalState.dragAngleDeg + lag)

        val effectiveBendTarget = if (phase == BrushContactPhase.LIFT_OFF) 0f else target.bend
        val loadingBend = effectiveBendTarget >= previous.bend
        val bend = approach(
            previous.bend,
            effectiveBendTarget,
            response(dtMs, if (loadingBend) deformationTauMs else recoveryTauMs),
        )

        val splitTauMs = lerp(320f, 28f, cfg.splitResponse * stiffness)
        val effectiveSplitLoad = if (phase == BrushContactPhase.LIFT_OFF || touchdown > 0.5f) {
            0f
        } else {
            maxOf(target.splitLoad, previous.splitDrive * reversal * cfg.reversalPersistence)
        }
        val splitDrive = approach(
            previous.splitDrive,
            effectiveSplitLoad,
            response(dtMs, if (effectiveSplitLoad >= previous.splitDrive) splitTauMs else recoveryTauMs),
        )
        val splitLatched = when {
            phase == BrushContactPhase.LIFT_OFF -> false
            previous.splitLatched && reversal > 0.05f -> true
            previous.splitLatched -> splitDrive > cfg.rejoinThreshold
            else -> splitDrive >= cfg.splitThreshold
        }
        val baseSplitTarget = if (splitLatched && target.edgeT > 0f) {
            ((splitDrive - cfg.rejoinThreshold) / (1f - cfg.rejoinThreshold).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
        } else 0f
        val splitTarget = if (reversal > 0.05f && previous.splitLatched) {
            maxOf(baseSplitTarget, previous.splitAmount * cfg.reversalPersistence)
        } else baseSplitTarget
        val splitAmount = approach(
            previous.splitAmount,
            splitTarget,
            response(dtMs, if (splitTarget >= previous.splitAmount) splitTauMs else recoveryTauMs),
        )

        val side = when {
            identity.rootLateralFraction < 0f -> -1f
            identity.rootLateralFraction > 0f -> 1f
            else -> 0f
        }
        val splitSeparation = side * cfg.splitSeparation * splitAmount * (0.4f + target.edgeT * 0.6f)
        var targetSeparation = target.cohesiveSeparationFraction + splitSeparation
        if (phase == BrushContactPhase.LIFT_OFF) targetSeparation = 0f
        targetSeparation *= (1f - touchdown * 0.25f)
        val loadingSeparation = abs(targetSeparation) >= abs(previous.separationFraction)
        val separation = approachSigned(
            previous.separationFraction,
            targetSeparation,
            response(dtMs, if (loadingSeparation) deformationTauMs else recoveryTauMs) *
                directionalHysteresis(previous.separationFraction, targetSeparation, cfg.hysteresis),
        )

        var trailTarget = if (phase == BrushContactPhase.LIFT_OFF) 0f else target.trailingFraction
        trailTarget *= (1f - touchdown * 0.8f)
        val loadingTrail = trailTarget >= previous.trailingFraction
        val trailing = approachSigned(
            previous.trailingFraction,
            trailTarget,
            response(dtMs, if (loadingTrail) deformationTauMs else recoveryTauMs),
        )

        return BrushTuftMechanicalState(
            id = identity.id,
            initialized = true,
            dragAngleDeg = tuftAngle,
            bend = bend,
            separationFraction = separation,
            trailingFraction = trailing,
            splitDrive = splitDrive,
            splitAmount = splitAmount,
            splitLatched = splitLatched,
            contactAgeMs = ageMs,
            touchdownAmount = touchdown,
            liftAmount = lift,
            reversalImpulse = reversal,
            lastGlobalDragAngleDeg = globalState.dragAngleDeg,
        )
    }

    private fun contactFor(
        identity: BrushTuftIdentity,
        dragAngleDeg: Float,
        globalDragAngleDeg: Float,
        lateralFraction: Float,
        trailingFraction: Float,
        splitAmount: Float,
        touchdownAmount: Float,
        liftAmount: Float,
        reversalImpulse: Float,
        cfg: BrushTuftConfig,
    ): BrushTuftContact {
        val localAngleDeg = normalizeDegrees(dragAngleDeg + identity.angleBiasDeg)
        val angleRad = localAngleDeg * TUFT_DEG_TO_RAD
        val dragX = cos(angleRad)
        val dragY = sin(angleRad)
        val lateralX = -dragY
        val lateralY = dragX
        val x = lateralX * lateralFraction +
            dragX * identity.rootLongitudinalFraction -
            dragX * trailingFraction
        val y = lateralY * lateralFraction +
            dragY * identity.rootLongitudinalFraction -
            dragY * trailingFraction
        val baseRadiusScale = (
            cfg.tuftWidthScale * identity.widthScale / cfg.count.toFloat()
            ).coerceIn(0.02f, 1f)
        val touchdownScale = 1f + touchdownAmount * cfg.touchdownCompression
        val liftScale = lerp(1f, cfg.liftRadiusFloor, liftAmount)
        return BrushTuftContact(
            id = identity.id,
            rootLateralFraction = identity.rootLateralFraction,
            offsetXFraction = x,
            offsetYFraction = y,
            radiusScale = (baseRadiusScale * touchdownScale * liftScale).coerceAtLeast(0.01f),
            alphaScale = 1f,
            angleOffsetDeg = wrapSignedDegrees(localAngleDeg - globalDragAngleDeg),
            stiffnessScale = identity.stiffnessScale,
            splitAmount = splitAmount,
            touchdownAmount = touchdownAmount,
            liftAmount = liftAmount,
            reversalImpulse = reversalImpulse,
            rootLongitudinalFraction = identity.rootLongitudinalFraction,
            morphology = cfg.morphology,
        )
    }

    private fun directionalHysteresis(current: Float, target: Float, hysteresis: Float): Float {
        if (current == 0f || target == 0f || current * target >= 0f) return 1f
        return (1f - hysteresis * 0.85f).coerceIn(0.1f, 1f)
    }

    private fun response(dtMs: Float, tauMs: Float): Float =
        1f - exp((-dtMs.coerceAtLeast(0f) / tauMs.coerceAtLeast(1f)).toDouble()).toFloat()

    private fun approach(current: Float, target: Float, amount: Float): Float =
        (current + (target - current) * amount.coerceIn(0f, 1f)).coerceIn(0f, 1f)

    private fun approachSigned(current: Float, target: Float, amount: Float): Float =
        current + (target - current) * amount.coerceIn(0f, 1f)

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
