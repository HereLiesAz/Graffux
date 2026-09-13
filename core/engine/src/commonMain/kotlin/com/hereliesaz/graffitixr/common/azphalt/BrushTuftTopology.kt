package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
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
    /** Relative width assigned to each bundle for the later split-tip renderer. */
    val tuftWidthScale: Float = 1f,
) {
    fun sanitized(): BrushTuftConfig = copy(
        count = count.coerceIn(1, 16),
        rootSpan = rootSpan.coerceIn(0f, 1.5f),
        cohesion = cohesion.coerceIn(0f, 1f),
        splayResponse = splayResponse.coerceIn(0f, 2f),
        bendDifferential = bendDifferential.coerceIn(0f, 0.5f),
        tuftWidthScale = tuftWidthScale.coerceIn(0.1f, 2f),
    )

    fun isActive(): Boolean = enabled && count > 1
}

/** Stable ferrule-local identity. No RNG participates in layout. */
data class BrushTuftIdentity(
    val id: Int,
    /** Lateral root position relative to brush center, in brush-diameter fractions. */
    val rootLateralFraction: Float,
    /** Edge bundles are slightly softer than center bundles, but retain stable identities. */
    val stiffnessScale: Float,
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
     * Resolve the stable ferrule topology against the current global mechanical contact.
     *
     * Global drag/bend/compression remain authoritative. Tufts only add coherent relative contact
     * structure: outer roots separate under splay and softer outer bundles trail slightly farther
     * under bend. Cohesion suppresses both deviations instead of injecting noise.
     */
    fun resolve(
        state: BrushMechanicalState,
        contact: BrushContactState,
        config: BrushTuftConfig,
    ): List<BrushTuftContact> {
        val cfg = config.sanitized()
        val identities = layout(cfg)
        if (identities.isEmpty()) return emptyList()

        val angleRad = state.dragAngleDeg * TUFT_DEG_TO_RAD
        val dragX = cos(angleRad)
        val dragY = sin(angleRad)
        val lateralX = -dragY
        val lateralY = dragX
        val freeMotion = 1f - cfg.cohesion
        val splayGain = 1f + contact.splay * cfg.splayResponse * (0.35f + freeMotion * 0.65f)
        val baseRadiusScale = (cfg.tuftWidthScale / cfg.count.toFloat()).coerceIn(0.04f, 1f)

        return identities.map { tuft ->
            val lateral = tuft.rootLateralFraction * splayGain
            val softness = 1f - tuft.stiffnessScale
            val trailing = contact.bend * cfg.bendDifferential * softness * (0.25f + freeMotion * 0.75f)
            val x = lateralX * lateral - dragX * trailing
            val y = lateralY * lateral - dragY * trailing
            BrushTuftContact(
                id = tuft.id,
                rootLateralFraction = tuft.rootLateralFraction,
                offsetXFraction = x,
                offsetYFraction = y,
                radiusScale = baseRadiusScale,
                alphaScale = 1f,
                angleOffsetDeg = 0f,
                stiffnessScale = tuft.stiffnessScale,
            )
        }
    }
}
