package com.hereliesaz.graffitixr.common.azphalt

/**
 * Runtime cost policy for physical bristle populations.
 *
 * The tier is deliberately resolved outside the brush preset and applied to the per-stroke brush
 * snapshot. That keeps presets portable while making the exact cap part of the brush definition
 * that live rendering and canonical replay both consume.
 */
enum class BrushPerformanceTier(
    val maxMechanicalTufts: Int,
    val maxPreviewCells: Int,
) {
    /** Low-RAM / constrained devices: bounded enough that max brush diameter cannot explode cost. */
    CONSTRAINED(maxMechanicalTufts = 16, maxPreviewCells = 96),

    /** Mid-range devices: retains substantially more physical contact detail with a fixed ceiling. */
    BALANCED(maxMechanicalTufts = 32, maxPreviewCells = 192),

    /** High-capability/default path. Existing brush-authored limits remain authoritative. */
    FULL(maxMechanicalTufts = 96, maxPreviewCells = 1024),
}

/**
 * Returns the deterministic per-stroke brush snapshot for [tier].
 *
 * This does not randomly throw away already-created hairs. It lowers the physical population's
 * authored maximum before topology generation, so the existing deterministic layout simply creates
 * a smaller stable population. The configured minimum is always honored, and non-physical brushes
 * are returned unchanged.
 */
fun AzphaltBrush.cappedForPerformanceTier(tier: BrushPerformanceTier): AzphaltBrush {
    val contactConfig = contact
    val tip = contactConfig.tipGeometry
    val population = tip.population
    if (!population.enabled || tip.kind != BrushTipKind.BRISTLE) return this

    val sanitized = population.sanitized()
    val mechanicalCap = tier.maxMechanicalTufts
        .coerceAtLeast(sanitized.minMechanicalTufts)
        .coerceAtMost(sanitized.maxMechanicalTufts)
    val previewCap = tier.maxPreviewCells
        .coerceAtLeast(16)
        .coerceAtMost(sanitized.maxPreviewCells)

    if (mechanicalCap == sanitized.maxMechanicalTufts && previewCap == sanitized.maxPreviewCells) {
        return this
    }

    return copy(
        contact = contactConfig.copy(
            tipGeometry = tip.copy(
                population = population.copy(
                    maxMechanicalTufts = mechanicalCap,
                    maxPreviewCells = previewCap,
                ),
            ),
        ),
    )
}
