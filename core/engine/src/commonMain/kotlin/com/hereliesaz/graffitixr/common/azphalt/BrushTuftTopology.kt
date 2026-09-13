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
    /**
     * Emit one ordinary renderer-facing [Dab] per resolved bundle.
     *
     * False is the compatibility default: topology can be computed/tested without changing pixels.
     * Turning this on is the M2 split-contact opt-in; CPU and Vulkan continue consuming the same
     * ordinary Dab contract and do not need their own tuft interpretation.
     */
    val emitTuftDabs: Boolean = false,
) {
    fun sanitized(): BrushTuftConfig = copy(
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
    )

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
     * the target geometry that persistent tuft state approaches during a real stroke.
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
                lateralFraction = tuft.rootLateralFraction + target.separationFraction,
                trailingFraction = target.trailingFraction,
                cfg = cfg,
            )
        }
    }

    /**
     * Incremental per-tuft mechanics. Stable bundle identities keep their own bend, separation,
     * trailing displacement and drag direction. Stiffness, hysteresis and recovery mediate the
     * transition instead of regenerating independent bundle offsets at every dab.
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
                BrushTuftMechanicalState(
                    id = identity.id,
                    initialized = true,
                    dragAngleDeg = normalizeDegrees(state.dragAngleDeg),
                    bend = target.bend,
                    separationFraction = target.separationFraction,
                    trailingFraction = target.trailingFraction,
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
                cfg = cfg,
            )
        }

        return BrushTuftMechanicalStep(nextStates, contacts)
    }

    private data class TuftTargets(
        val bend: Float,
        val separationFraction: Float,
        val trailingFraction: Float,
    )

    private fun targets(
        identity: BrushTuftIdentity,
        contact: BrushContactState,
        cfg: BrushTuftConfig,
    ): TuftTargets {
        val freeMotion = 1f - cfg.cohesion
        val splayGain = 1f + contact.splay * cfg.splayResponse * (0.35f + freeMotion * 0.65f)
        val lateral = identity.rootLateralFraction * splayGain
        val separation = lateral - identity.rootLateralFraction
        val softness = 1f - identity.stiffnessScale
        val bend = (contact.bend * (1f + softness * 0.2f)).coerceIn(0f, 1f)
        val trailing = bend * cfg.bendDifferential * softness * (0.25f + freeMotion * 0.75f)
        return TuftTargets(
            bend = bend,
            separationFraction = separation,
            trailingFraction = trailing,
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

        val loadingSeparation = abs(target.separationFraction) >= abs(previous.separationFraction)
        val separationResponse = response(dtMs, if (loadingSeparation) deformationTauMs else recoveryTauMs)
        val separation = approachSigned(
            previous.separationFraction,
            target.separationFraction,
            separationResponse * directionalHysteresis(previous.separationFraction, target.separationFraction, cfg.hysteresis),
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
        )
    }

    private fun contactFor(
        identity: BrushTuftIdentity,
        dragAngleDeg: Float,
        globalDragAngleDeg: Float,
        lateralFraction: Float,
        trailingFraction: Float,
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
