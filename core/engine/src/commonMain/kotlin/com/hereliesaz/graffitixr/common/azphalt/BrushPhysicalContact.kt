package com.hereliesaz.graffitixr.common.azphalt

/**
 * Whether this contact definition needs the stateful mechanical path before a concrete brush size
 * has been resolved. A physical bristle population is itself an explicit opt-in: it must never fall
 * back to the historical static-stamp path merely because [BrushContactConfig.enabled] was left at
 * its legacy default.
 */
fun BrushContactConfig.requiresDynamicContact(): Boolean =
    isActive() || (
        tipGeometry.kind == BrushTipKind.BRISTLE &&
            tipGeometry.population.enabled
        )

/**
 * Freeze size-dependent physical topology once, at stroke construction time.
 *
 * Population is derived from the selected/base brush diameter, not a pressure/taper-varying dab
 * diameter, so a stroke never creates or destroys hairs while it is being drawn. Pressure, lean,
 * splay and lift change engagement/deformation only.
 */
fun BrushContactConfig.resolvedForBrushDiameter(
    diameterPx: Float,
    legacyTipRatio: Float,
): BrushContactConfig {
    val resolved = BrushTipTopology.resolvedContactConfig(
        contact = this,
        diameterPx = diameterPx,
        legacyTipRatio = legacyTipRatio,
    )
    val physicalPopulation = resolved.tipGeometry.kind == BrushTipKind.BRISTLE &&
        resolved.tipGeometry.population.enabled
    return if (physicalPopulation) resolved.copy(enabled = true) else resolved
}
