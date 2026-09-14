package com.hereliesaz.graffitixr.common.azphalt

import kotlin.math.floor

/**
 * Renderer-independent description of the static canvas tooth used by Phase 3 deposition.
 *
 * The texture coordinates deliberately mirror the existing canvas-locked grain contract:
 * absolute canvas coordinates are divided by [textureScale], offset, floored, and wrapped into a
 * repeating R8 tile. This keeps substrate texture semantics aligned with the brush engine's
 * existing grain/texture system instead of creating an unrelated canvas-noise coordinate model.
 *
 * [baseHeight] and [heightScale] are normalized penetration thresholds, not literal world-space
 * millimetres. A sampled height of 0 is easiest to reach; 1 requires maximum resolved contact
 * depth. [absorbency] is carried now so the same profile can feed Phase 4 wetness/transport later,
 * but dry Phase 3 deposition intentionally does not invent wet-flow behavior from it yet.
 */
data class SubstrateProfile(
    val baseHeight: Float = 0f,
    val heightScale: Float = 0f,
    val absorbency: Float = 0f,
    val textureScale: Float = 1f,
    val textureOffsetX: Float = 0f,
    val textureOffsetY: Float = 0f,
) {
    fun sanitized(): SubstrateProfile = copy(
        baseHeight = baseHeight.coerceIn(0f, 1f),
        heightScale = heightScale.coerceIn(0f, 1f),
        absorbency = absorbency.coerceIn(0f, 1f),
        textureScale = textureScale.coerceAtLeast(0.05f),
    )

    fun sampleWithoutTexture(): SubstrateSample {
        val profile = sanitized()
        return SubstrateSample(profile.baseHeight, profile.absorbency)
    }

    companion object {
        /** Exact smooth compatibility substrate: no tooth barrier and no wet-material assumptions. */
        val SMOOTH = SubstrateProfile()
    }
}

/** One normalized static substrate sample at a canvas coordinate. */
data class SubstrateSample(
    val height: Float = 0f,
    val absorbency: Float = 0f,
) {
    fun sanitized(): SubstrateSample = copy(
        height = height.coerceIn(0f, 1f),
        absorbency = absorbency.coerceIn(0f, 1f),
    )
}

/**
 * Immutable repeating R8 substrate tile.
 *
 * [heightR8] is required and stores normalized tooth height. [absorbencyR8] is optional; when it is
 * absent the profile's scalar absorbency is used. Arrays are copied on construction so replay cannot
 * be changed by a caller mutating its source asset after a stroke has started.
 */
class SubstrateField(
    val width: Int,
    val height: Int,
    heightR8: ByteArray,
    absorbencyR8: ByteArray? = null,
) {
    private val heightBytes = heightR8.copyOf()
    private val absorbencyBytes = absorbencyR8?.copyOf()

    init {
        require(width > 0) { "SubstrateField.width must be positive" }
        require(height > 0) { "SubstrateField.height must be positive" }
        require(heightBytes.size == width * height) {
            "SubstrateField heightR8 must contain exactly width*height samples"
        }
        require(absorbencyBytes == null || absorbencyBytes.size == width * height) {
            "SubstrateField absorbencyR8 must contain exactly width*height samples when present"
        }
    }

    fun sample(canvasX: Float, canvasY: Float, profile: SubstrateProfile): SubstrateSample {
        val cfg = profile.sanitized()
        val tx = wrap(floor(canvasX / cfg.textureScale + cfg.textureOffsetX).toInt(), width)
        val ty = wrap(floor(canvasY / cfg.textureScale + cfg.textureOffsetY).toInt(), height)
        val index = ty * width + tx
        val tooth = unsigned(heightBytes[index]) / 255f
        val sampledAbsorbency = absorbencyBytes?.let { unsigned(it[index]) / 255f } ?: cfg.absorbency
        return SubstrateSample(
            height = (cfg.baseHeight + tooth * cfg.heightScale).coerceIn(0f, 1f),
            absorbency = sampledAbsorbency.coerceIn(0f, 1f),
        )
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

    private fun wrap(value: Int, size: Int): Int {
        val remainder = value % size
        return if (remainder < 0) remainder + size else remainder
    }
}

/** Result of applying the Phase 3 contact-depth gate at one dab/sample location. */
data class SubstrateDeposition(
    /** Effective threshold after existing local paint height has filled some of the tooth/valley. */
    val penetrationBarrier: Float,
    /** Whether the resolved contact reaches the sampled substrate at full substrate response. */
    val penetrates: Boolean,
    /** Final coverage multiplier after blending with [PaintMedium.substrateResponse]. */
    val coverage: Float,
    /** Normalized material amount available to deposit after load and substrate gating. */
    val deposition: Float,
    /** Sampled now for the later wetness/transport phase; dry Phase 3 does not reinterpret it. */
    val absorbency: Float,
)

/**
 * Deterministic CPU reference for substrate-aware deposition.
 *
 * The core rule follows the roadmap directly:
 *
 * `contactDepth >= substrateHeight - localPaintHeightContribution`
 *
 * [PaintMedium.substrateResponse] blends from the historical ungated result (0) to full substrate
 * gating (1), so every existing medium remains byte/behavior compatible until it explicitly opts
 * into texture interaction. Reservoir load and [PaintMedium.depositionRate] remain independent
 * multiplicative material limits rather than being hidden inside the substrate texture itself.
 */
object SubstrateDepositionModel {
    fun resolve(
        contactDepth: Float,
        localPaintHeightContribution: Float,
        reservoir: BrushReservoirState,
        medium: PaintMedium,
        substrate: SubstrateSample,
    ): SubstrateDeposition {
        val state = reservoir.sanitized()
        val material = medium.sanitized()
        val sample = substrate.sanitized()
        val depth = contactDepth.coerceIn(0f, 1f)
        val paintHeight = localPaintHeightContribution.coerceAtLeast(0f)
        val barrier = (sample.height - paintHeight).coerceIn(0f, 1f)
        val penetrates = depth >= barrier
        val substrateCoverage = if (penetrates) 1f else 0f
        val response = material.substrateResponse
        val coverage = ((1f - response) + substrateCoverage * response).coerceIn(0f, 1f)
        val deposition = (material.depositionRate * state.load * coverage).coerceIn(0f, 1f)
        return SubstrateDeposition(
            penetrationBarrier = barrier,
            penetrates = penetrates,
            coverage = coverage,
            deposition = deposition,
            absorbency = sample.absorbency,
        )
    }

    /** Convenience entry point for already-resolved renderer-neutral dabs. */
    fun resolveDab(
        dab: Dab,
        localPaintHeightContribution: Float,
        reservoir: BrushReservoirState,
        medium: PaintMedium,
        profile: SubstrateProfile,
        field: SubstrateField? = null,
    ): SubstrateDeposition {
        val substrate = field?.sample(dab.x, dab.y, profile) ?: profile.sampleWithoutTexture()
        return resolve(
            contactDepth = dab.contactDepth,
            localPaintHeightContribution = localPaintHeightContribution,
            reservoir = reservoir,
            medium = medium,
            substrate = substrate,
        )
    }
}
