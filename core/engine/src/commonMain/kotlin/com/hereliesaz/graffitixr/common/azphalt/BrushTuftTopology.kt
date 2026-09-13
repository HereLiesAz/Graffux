package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val TUFT_DEG_TO_RAD = 0.017453292f

/**
 * Coarse bristle-bundle topology for M2 brush mechanics.
 *
 * This is intentionally not a random scatter system. A tuft has a stable identity and a stable
 * ferrule-local root for the entire stroke and across deterministic replay. The topology only
 * describes brush-internal contact structure; renderers do not reinterpret it.
 */
@Serializable
data class BrushTuftConfig(
    val enabled: Boolean = false,
    /** Number of stable coarse bundles across the brush. */
    val count: Int = 5,
    /** Total lateral span of tuft roots, in brush-diameter fractions. */
    val rootSpan: Float = 0.72f,
    /** 0 allows free bundle separation, 1 strongly preserves the rest arrangement. */
    val cohesion: Float = 0.72f,
    /** How strongly global contact splay pushes outer bundles farther apart. */
    val splayResponse: Float = 0.8f,
    /** Differential trailing displacement of softer/outer bundles under bend. */
    val bendDifferential: Float = 0.12f,
    /** Relative width assigned to each bundle for split-tip rendering. */
    val tuftWidthScale: Float = 1f,
    /** 0 = very slow bundle deformation, 1 = quickly follows its mechanical target. */
    val deformationResponse: Float = 0.62f,
    /** Resistance to a bundle changing an established drag direction. */
    val hysteresis: Float = 0.45f,
    /** How quickly a bundle returns toward its rest separation/bend as loading falls. */
    val recovery: Float = 0.68f,
    /** Maximum additional angular lag of one tuft behind the global brush contact. */
    val maxLagDeg: Float = 22f,
    /** Filtered split load at which a cohesive tuft breaks away from its neighboring bundle mass. */
    val splitThreshold: Float = 0.46f,
    /** Lower load at which an already split tuft is allowed to rejoin. Must not exceed splitThreshold. */
    val rejoinThreshold: Float = 0.28f,
    /** How quickly rake/splay loading accumulates into or releases split-tip deformation. */
    val splitResponse: Float = 0.7f,
    /** Maximum extra lateral displacement of a fully split outer tuft, in diameter fractions. */
    val splitSeparation: Float = 0.16f,
    /** Contribution of bend/rake to split loading; the remainder comes from contact splay. */
    val splitBendWeight: Float = 0.45f,
    /**
     * Emit one ordinary renderer-facing [Dab] per resolved bundle.
     *
     * False is the compatibility default: topology can be computed/tested without changing pixels.
     * Turning this on is the M2 split-contact opt-in; CPU and Vulkan continue consuming the same
     * ordinary Dab contract and do not need their own tuft interpretation.
     */
    val emitTuftDabs: Boolean = false,
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
        )
    }

    fun isActive(): Boolean = enabled && count > 1
    fun emitsDabs(): Boolean = isActive() && emitTuftDabs
}

/** Stable ferrule-local identity. No RNG participates in layout. */
data class BrushTuftIdentity(
    val id: Int,
    /** Lateral root position relative to brush center, in brush-diameter fractions. */
    val rootLateralFraction: Float,
    /** Edge bundles are slightly softer than center bundles, but retain stable identities. */
    val stiffnessScale: Float,
)

/** Persistent deformation state for one stable tuft identity. */
data class BrushTuftMechanicalState(
    val id: Int,
    val initialized: Boolean = false,
    /** Current bundle drag direction. This may lag the global brush direction at corners/reversals. */
    val dragAngleDeg: Float = 0f,
    /** Per-bundle bend amount, retained across samples. */
    val bend: Float = 0f,
    /** Signed displacement away from the ferrule-local root. */
    val separationFraction: Float = 0f,
    /** Trailing displacement behind the tuft's own drag direction. */
    val trailingFraction: Float = 0f,
    /** Low-pass mechanical loading that must build before a tuft breaks away. */
    val splitDrive: Float = 0f,
    /** Continuous 0..1 split deformation, separately filtered from the latch decision. */
    val splitAmount: Float = 0f,
    /** Hysteretic split state: set above splitThreshold, cleared only below rejoinThreshold. */
    val splitLatched: Boolean = false,
)

/**
 * Renderer-independent resolved bundle contact relative to the already-resolved primary contact
 * center. Offsets are brush-diameter fractions in canvas x/y coordinates.
 */
data class BrushTuftContact(
    val id: Int,
    val rootLateralFraction: Float,
    val offsetXFraction: Float,
    val offsetYFraction: Float,
    val radiusScale: Float,
    val alphaScale: Float,
    val angleOffsetDeg: Float,
    val stiffnessScale: Float,
    /** 0..1 persistent split-tip deformation for diagnostics/future morphology. */
    val splitAmount: Float = 0f,
)

data class BrushTuftMechanicalStep(
    val states: List<BrushTuftMechanicalState>,
    val contacts: List<BrushTuftContact>,
)

object BrushTuftTopology {
    /** Stable identities depend only on sanitized brush configuration. */
    fun layout(config: BrushTuftConfig): List<BrushTuftIdentity> {
        val cfg = config.sanitized()
        if (!cfg.isActive()) return emptyList()
        val count = cfg.count
        val halfSpan = cfg.rootSpan * 0.5f
        return List(count) { index ->
            val normalized = if (count == 1) 0f else index.toFloat() / (count - 1).toFloat()
            val root = (normalized * 2f - 1f) * halfSpan
            val edgeT = if (halfSpan > 0f) (abs(root) / halfSpan).coerceIn(0f, 1f) else 0f
            BrushTuftIdentity(
                id = index,
                rootLateralFraction = root,
                stiffnessScale = 1f - edgeT * 0.2f,
            )
        }
    }

    /**
     * Stateless reference resolution. This remains useful for topology previews/tests and defines
     * the cohesive target geometry persistent tuft state approaches during a real stroke. Split
     * transitions intentionally require history and therefore remain inactive in this function.
     */
    fun resolve(
        state: BrushMechanicalState,
        contact: BrushContactState,
        config: BrushTuftConfig,
    ): List<BrushTuftContact> {
        val cfg = config.sanitized()
        val identities = layout(cfg)
        if (identities.isEmpty()) return emptyList()
        return identities.map { tuft ->
            val target = targets(tuft, contact, cfg)
            contactFor(
                identity = tuft,
                dragAngleDeg = state.dragAngleDeg,
                globalDragAngleDeg = state.dragAngleDeg,
                lateralFraction = tuft.rootLateralFraction + target.cohesiveSeparationFraction,
                trailingFraction = target.trailingFraction,
                splitAmount = 0f,
                cfg = cfg,
            )
        }
    }

    /**
     * Incremental per-tuft mechanics. Stable bundle identities keep their own bend, separation,
     * trailing displacement, drag direction and split state. Split loading is low-pass filtered,
     * then a breakaway/rejoin threshold pair supplies real hysteresis: a tuft does not chatter in
     * and out of a split merely because instantaneous splay hovers near one threshold.
     */
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
                // New contact begins cohesive even under a hard touchdown. Split drive must build
                // through subsequent samples before the bundle can break away.
                BrushTuftMechanicalState(
                    id = identity.id,
                    initialized = true,
                    dragAngleDeg = normalizeDegrees(state.dragAngleDeg),
                    bend = target.bend,
                    separationFraction = target.cohesiveSeparationFraction,
                    trailingFraction = target.trailingFraction,
                    splitDrive = 0f,
                    splitAmount = 0f,
                    splitLatched = false,
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

    private fun targets(
        identity: BrushTuftIdentity,
        contact: BrushContactState,
        cfg: BrushTuftConfig,
    ): TuftTargets {
        val halfSpan = cfg.rootSpan * 0.5f
        val edgeT = if (halfSpan > 0f) {
            (abs(identity.rootLateralFraction) / halfSpan).coerceIn(0f, 1f)
        } else 0f
        val freeMotion = 1f - cfg.cohesion
        val splayGain = 1f + contact.splay * cfg.splayResponse * (0.35f + freeMotion * 0.65f)
        val lateral = identity.rootLateralFraction * splayGain
        val cohesiveSeparation = lateral - identity.rootLateralFraction
        val softness = 1f - identity.stiffnessScale
        val bend = (contact.bend * (1f + softness * 0.2f)).coerceIn(0f, 1f)
        val trailing = bend * cfg.bendDifferential * softness * (0.25f + freeMotion * 0.75f)

        // Center bundles act as the cohesive anchor. Outer bundles see more separating load, while
        // cohesion reduces the effective rake/splay available to tear a tuft away from the mass.
        val rawLoad = (
            contact.splay * (1f - cfg.splitBendWeight) + contact.bend * cfg.splitBendWeight
            ).coerceIn(0f, 1f)
        val edgeExposure = if (edgeT <= 1e-4f) 0f else 0.35f + edgeT * 0.65f
        val cohesionResistance = 1f - cfg.cohesion * 0.45f
        val splitLoad = (rawLoad * edgeExposure * cohesionResistance).coerceIn(0f, 1f)

        return TuftTargets(
            bend = bend,
            cohesiveSeparationFraction = cohesiveSeparation,
            trailingFraction = trailing,
            splitLoad = splitLoad,
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

        val turn = wrapSignedDegrees(globalState.dragAngleDeg - previous.dragAngleDeg)
        val turnT = (abs(turn) / 180f).coerceIn(0f, 1f)
        val angleResponse = response(dtMs, deformationTauMs) *
            (1f - cfg.hysteresis * turnT * 0.9f).coerceIn(0.05f, 1f)
        var tuftAngle = previous.dragAngleDeg + turn * angleResponse
        var lag = wrapSignedDegrees(tuftAngle - globalState.dragAngleDeg)
        lag = lag.coerceIn(-cfg.maxLagDeg, cfg.maxLagDeg)
        tuftAngle = normalizeDegrees(globalState.dragAngleDeg + lag)

        val loadingBend = target.bend >= previous.bend
        val bendResponse = response(dtMs, if (loadingBend) deformationTauMs else recoveryTauMs)
        val bend = approach(previous.bend, target.bend, bendResponse)

        val splitTauMs = lerp(320f, 28f, cfg.splitResponse * stiffness)
        val splitDriveResponse = response(
            dtMs,
            if (target.splitLoad >= previous.splitDrive) splitTauMs else recoveryTauMs,
        )
        val splitDrive = approach(previous.splitDrive, target.splitLoad, splitDriveResponse)
        val splitLatched = if (previous.splitLatched) {
            splitDrive > cfg.rejoinThreshold
        } else {
            splitDrive >= cfg.splitThreshold
        }
        val splitTarget = if (splitLatched && target.edgeT > 0f) {
            ((splitDrive - cfg.rejoinThreshold) / (1f - cfg.rejoinThreshold).coerceAtLeast(0.01f))
                .coerceIn(0f, 1f)
        } else 0f
        val splitAmountResponse = response(
            dtMs,
            if (splitTarget >= previous.splitAmount) splitTauMs else recoveryTauMs,
        )
        val splitAmount = approach(previous.splitAmount, splitTarget, splitAmountResponse)
        val side = when {
            identity.rootLateralFraction < 0f -> -1f
            identity.rootLateralFraction > 0f -> 1f
            else -> 0f
        }
        val splitSeparation = side * cfg.splitSeparation * splitAmount * (0.4f + target.edgeT * 0.6f)
        val targetSeparation = target.cohesiveSeparationFraction + splitSeparation

        val loadingSeparation = abs(targetSeparation) >= abs(previous.separationFraction)
        val separationResponse = response(dtMs, if (loadingSeparation) deformationTauMs else recoveryTauMs)
        val separation = approachSigned(
            previous.separationFraction,
            targetSeparation,
            separationResponse * directionalHysteresis(previous.separationFraction, targetSeparation, cfg.hysteresis),
        )

        val loadingTrail = target.trailingFraction >= previous.trailingFraction
        val trailResponse = response(dtMs, if (loadingTrail) deformationTauMs else recoveryTauMs)
        val trailing = approachSigned(previous.trailingFraction, target.trailingFraction, trailResponse)

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
        )
    }

    private fun contactFor(
        identity: BrushTuftIdentity,
        dragAngleDeg: Float,
        globalDragAngleDeg: Float,
        lateralFraction: Float,
        trailingFraction: Float,
        splitAmount: Float,
        cfg: BrushTuftConfig,
    ): BrushTuftContact {
        val angleRad = dragAngleDeg * TUFT_DEG_TO_RAD
        val dragX = cos(angleRad)
        val dragY = sin(angleRad)
        val lateralX = -dragY
        val lateralY = dragX
        val x = lateralX * lateralFraction - dragX * trailingFraction
        val y = lateralY * lateralFraction - dragY * trailingFraction
        val baseRadiusScale = (cfg.tuftWidthScale / cfg.count.toFloat()).coerceIn(0.04f, 1f)
        return BrushTuftContact(
            id = identity.id,
            rootLateralFraction = identity.rootLateralFraction,
            offsetXFraction = x,
            offsetYFraction = y,
            radiusScale = baseRadiusScale,
            alphaScale = 1f,
            angleOffsetDeg = wrapSignedDegrees(dragAngleDeg - globalDragAngleDeg),
            stiffnessScale = identity.stiffnessScale,
            splitAmount = splitAmount,
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
