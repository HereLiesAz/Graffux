package com.hereliesaz.graffitixr.common.azphalt

/**
 * Stamp-brush presets that ship with the app itself -- no extension install, no Brush Studio
 * setup. Without these, a fresh install's only paintable options were the legacy Round tool
 * (which never touches this engine at all) and Brush Studio (which requires the user to build a
 * brush from parameters before they can paint with one), so the entire native stamp engine --
 * dab placement, sensor dynamics, Airbrush, Impasto, GPU stamping -- was unreachable by default.
 *
 * Deliberately have no [AzphaltBrush.shapePath]/[AzphaltBrush.grainPath]: those resolve through
 * an installed extension's own asset bundle ([com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository.assetFilePath]),
 * which these presets don't have. A null tip already renders a generated round mask
 * (`BrushTipMaskCache.tipMask(null, ...)`), so these still exercise the real engine -- spacing,
 * hardness falloff, sensor dynamics, Airbrush build-up -- without needing bundled bitmap assets.
 */
object BuiltInBrushes {
    val presets: List<AzphaltBrush> = listOf(
        // A soft, pressure-responsive round -- the brush most painting apps default to. Tapers in
        // size and opacity as pressure eases off, the same "Pressure -> Size"/"Pressure -> Opacity"
        // combination Brush Studio's own quick-start presets offer.
        AzphaltBrush(
            name = "Soft Round",
            hardness = 0.35f,
            opacity = 0.9f,
            spacing = 0.08f,
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.3f, outputMax = 1f),
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.OPACITY, outputMin = 0.4f, outputMax = 1f),
            ),
        ),
        // A crisp, undynamic round -- inking/line work, where a stable width matters more than
        // pressure response.
        AzphaltBrush(
            name = "Hard Round",
            hardness = 1f,
            opacity = 1f,
            spacing = 0.06f,
        ),
        // A soft-edged, low-opacity tip with Airbrush build-up (item 13): holding the stroke still
        // keeps depositing paint, the same behaviour Krita/Procreate's own airbrush tools have.
        AzphaltBrush(
            name = "Airbrush",
            hardness = 0f,
            opacity = 0.35f,
            spacing = 0.15f,
            airbrushDabsPerSecond = 12f,
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.OPACITY, outputMin = 0.3f, outputMax = 1f),
            ),
        ),
    )
}
