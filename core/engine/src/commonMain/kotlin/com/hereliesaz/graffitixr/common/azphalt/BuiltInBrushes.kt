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
    /** Name of [round]; also `EditorUiState.activeBrushName`'s default (core/common can't see this
     *  module, so the literal is duplicated there -- BuiltInBrushesTest pins the two together). */
    const val DEFAULT_NAME = "Round"

    /** The Ink Pen's first dab, as a multiple of its resting dab size -- the whole start blot. */
    const val INK_PEN_BLOT_MAX_SIZE = 1.3f

    /**
     * The main brush: the plainest possible GPU stamp round -- near-hard edge, full opacity,
     * pressure -> size only, no build-up, no airbrush, no blot, no tip or grain asset. It is the
     * default selection on every platform and the reference every other brush, bundled or
     * imported, is felt against, so it deliberately carries no character of its own. Renders
     * through the same Vulkan stamp pipeline as every other stamp brush (stroke-max mode; see
     * GpuStampEngine.stampResolvedDabs), not the legacy Catmull-Rom Round.
     */
    val round: AzphaltBrush = AzphaltBrush(
        name = DEFAULT_NAME,
        hardness = 0.85f,
        opacity = 1f,
        spacing = 0.05f,
        dynamics = listOf(
            BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.25f, outputMax = 1f),
        ),
    )

    val presets: List<AzphaltBrush> = listOf(
        round,
        // A soft, pressure-responsive round -- the brush most painting apps default to. Tapers in
        // size and opacity as pressure eases off, the same "Pressure -> Size"/"Pressure -> Opacity"
        // combination Brush Studio's own quick-start presets offer. A mild hold-to-build-up (see
        // AzphaltBrush.airbrushDabsPerSecond's doc comment -- not just for the Airbrush preset
        // below) lets it pool a little more paint if the stroke pauses, the way a loaded soft brush
        // realistically would.
        AzphaltBrush(
            name = "Soft Round",
            hardness = 0.35f,
            opacity = 0.9f,
            spacing = 0.08f,
            airbrushDabsPerSecond = 4f,
            airbrushStillnessRadiusPx = 4f,
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
        // A soft-edged, low-opacity tip with hold-to-build-up (item 13): holding the stroke still
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
        // A loaded ink pen: touching down leaves a small blot, at most INK_PEN_BLOT_MAX_SIZE of the
        // resting dab, that fades into the line over the first 60 px -- plus a light hold-to-build-up
        // for a pause mid-stroke. Toned down at the owner's call (the start "big spots" report): it
        // used to reach ~9x the line width, 1.8x base times up to 2.5x dwell growth times up to 2x
        // for a sharp press, and nearly every stylus touchdown counted as sharp (pressure rises from
        // ~0 to working pressure in one ~8 ms sample). Dwell and sharpness growth are now off, so the
        // blot no longer depends on how the pen landed, and it has no extra jittered copies, whose
        // offsets would spread the spot past the cap.
        AzphaltBrush(
            name = "Ink Pen",
            hardness = 0.9f,
            opacity = 1f,
            spacing = 0.05f,
            airbrushDabsPerSecond = 3f,
            airbrushStillnessRadiusPx = 3f,
            blot = BrushBlot(
                lengthPx = 60f,
                sizeMultiplier = INK_PEN_BLOT_MAX_SIZE,
                opacityMultiplier = 1.2f,
            ),
        ),
    )
}
