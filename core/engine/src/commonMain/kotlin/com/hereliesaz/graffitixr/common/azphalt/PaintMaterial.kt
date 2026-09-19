package com.hereliesaz.graffitixr.common.azphalt

/**
 * Immutable material configuration. These are perceptual controls, not claims that Graffux is
 * solving literal rheology; more physical implementations can consume the same public contract.
 * Defaults deliberately describe the existing dry/legacy path.
 */
data class PaintMedium(
    val mixingModel: MaterialMixingModel = MaterialMixingModel.LEGACY_RGB,
    /** Relative resistance to flow, normalized 0..1. */
    val viscosity: Float = 0f,
    /** Yield/thixotropy-like resistance to post-deposition movement, normalized 0..1. */
    val yieldLikeStrength: Float = 0f,
    /** Normalized material drying rate per simulation time unit. */
    val dryingRate: Float = 0f,
    /** Fractional pickup tendency during brush/canvas contact. */
    val pickupRate: Float = 0f,
    /** Fractional deposition tendency during brush/canvas contact. */
    val depositionRate: Float = 1f,
    /** Contribution of deposited material to the existing 2.5D height channel. */
    val heightResponse: Float = 0f,
    /** Strength of substrate/tooth influence on deposition. */
    val substrateResponse: Float = 0f,
    // Appended after the original public constructor fields so old positional callers retain meaning.
    /** Bounded post-contact height leveling rate while material is wet, normalized 0..1. */
    val levelingRate: Float = 0f,
    /** Dry-state surface roughness used by Impasto-v2 presentation, normalized 0..1. */
    val baseRoughness: Float = 1f,
    /** White/specular response contributed by wetness; 0 preserves historical relief shading. */
    val wetSpecularStrength: Float = 0f,
) {
    fun sanitized(): PaintMedium = copy(
        viscosity = viscosity.coerceIn(0f, 1f),
        yieldLikeStrength = yieldLikeStrength.coerceIn(0f, 1f),
        levelingRate = levelingRate.coerceIn(0f, 1f),
        baseRoughness = baseRoughness.coerceIn(0f, 1f),
        wetSpecularStrength = wetSpecularStrength.coerceIn(0f, 1f),
        dryingRate = dryingRate.coerceAtLeast(0f),
        pickupRate = pickupRate.coerceIn(0f, 1f),
        depositionRate = depositionRate.coerceIn(0f, 1f),
        heightResponse = heightResponse.coerceAtLeast(0f),
        substrateResponse = substrateResponse.coerceIn(0f, 1f),
    )

    /** True only when this medium needs behavior beyond the historical color-only paint path. */
    val usesMaterialPath: Boolean
        get() = mixingModel != MaterialMixingModel.LEGACY_RGB ||
            viscosity != 0f || yieldLikeStrength != 0f || levelingRate != 0f ||
            baseRoughness != 1f || wetSpecularStrength != 0f || dryingRate != 0f || pickupRate != 0f ||
            depositionRate != 1f || heightResponse != 0f || substrateResponse != 0f
}

/**
 * Mutable-in-concept, value-typed per-stroke reservoir state. Callers evolve it by returning a
 * copied value, keeping replay deterministic and making state transitions straightforward to test.
 */
data class BrushReservoirState(
    val load: Float = 1f,
    val wetness: Float = 0f,
    val carriedColor: MaterialColor,
) {
    fun sanitized(): BrushReservoirState = copy(
        load = load.coerceIn(0f, 1f),
        wetness = wetness.coerceIn(0f, 1f),
        carriedColor = carriedColor.clamped(),
    )
}

/** Optional per-layer material channels. False/false/false is the allocation-free dry path. */
data class MaterialChannels(
    val hasHeight: Boolean = false,
    val hasWetness: Boolean = false,
    val hasStructure: Boolean = false,
) {
    val isColorOnly: Boolean get() = !hasHeight && !hasWetness && !hasStructure
}
