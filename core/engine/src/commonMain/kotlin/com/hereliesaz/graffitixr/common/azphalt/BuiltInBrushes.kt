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

    /** The original five: neutral references, one per basic behaviour. */
    val basics: List<AzphaltBrush> = listOf(
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

    // -- Tuning notes shared by every set below ------------------------------------------------
    // SPEED reads BrushSample.speedPxPerMs: ~0.2 is a careful line, ~1.5 a confident one, 3+ a
    // flick. TILT reads radians (0 upright .. ~1.2 laid well over). DISTANCE reads stroke pixels.
    // Multiplicative routes stack, so each brush keeps its product inside a range that never
    // fully vanishes (every outputMin is > 0) -- a finger with flat 1.0 pressure and no tilt must
    // still get a usable, characterful mark from every preset.

    /**
     * Aerosol: can control, not brush control. Paint pools where the can lingers (hold-to-build-up
     * plus sequential build-up), thins and widens as the can moves fast (SPEED inverted onto
     * opacity), and the softness is the cap, not the pressure. Pressure narrows the cone the way
     * feathering a cap does.
     */
    val aerosol: List<AzphaltBrush> = listOf(
        // Tight line cap: hard enough to letter with, soft enough to read as spray. Lingering
        // swells it into a dot; a fast pass ghosts out.
        AzphaltBrush(
            name = "Skinny Cap",
            hardness = 0.2f,
            opacity = 0.6f,
            spacing = 0.06f,
            buildUp = true,
            airbrushDabsPerSecond = 18f,
            airbrushStillnessRadiusPx = 4f,
            substrateResponse = 0.25f,
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.6f, outputMax = 1f),
                BrushSensorBinding(
                    sensor = BrushSensor.SPEED, parameter = BrushParameter.OPACITY,
                    inputMax = 2.5f, outputMin = 0.45f, outputMax = 1f, invert = true,
                ),
                BrushSensorBinding(
                    sensor = BrushSensor.SPEED, parameter = BrushParameter.SIZE,
                    inputMax = 2.5f, outputMin = 0.8f, outputMax = 1f, invert = true,
                ),
            ),
        ),
        // Wide fill cap: no core at all, a faint fog per pass that only goes solid with overlap.
        // The slight size jitter is the cone breathing.
        AzphaltBrush(
            name = "Fat Cap",
            hardness = 0f,
            opacity = 0.4f,
            spacing = 0.1f,
            sizeJitter = 0.05f,
            buildUp = true,
            airbrushDabsPerSecond = 14f,
            airbrushStillnessRadiusPx = 6f,
            substrateResponse = 0.2f,
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.7f, outputMax = 1f),
                BrushSensorBinding(
                    sensor = BrushSensor.SPEED, parameter = BrushParameter.OPACITY,
                    inputMax = 3f, outputMin = 0.35f, outputMax = 1f, invert = true,
                ),
            ),
        ),
        // The can pulled back: a barely-there haze for fades and glows. A fast sweep spreads it
        // wider (SPEED -> SIZE above 1), the way distance spreads a real cone.
        AzphaltBrush(
            name = "Mist",
            hardness = 0f,
            opacity = 0.12f,
            spacing = 0.2f,
            buildUp = true,
            airbrushDabsPerSecond = 20f,
            airbrushStillnessRadiusPx = 8f,
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.OPACITY, outputMin = 0.4f, outputMax = 1f),
                BrushSensorBinding(
                    sensor = BrushSensor.SPEED, parameter = BrushParameter.SIZE,
                    inputMax = 3f, outputMin = 1f, outputMax = 1.35f,
                ),
            ),
        ),
        // Overspray / a spitting cap: a field of separate droplets rather than a cone. Each dab is
        // a tiny droplet (RANDOM_DAB -> SIZE, skewed small), so scatter -- a fraction of the
        // RESOLVED droplet diameter -- is large to throw them across the whole brush width.
        AzphaltBrush(
            name = "Speckle",
            hardness = 0.7f,
            opacity = 0.75f,
            opacityJitter = 0.6f,
            spacing = 1.2f,
            scatter = 4.5f,
            scatterLongitudinal = 4.5f,
            count = 6,
            countJitter = 0.6f,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.RANDOM_DAB, parameter = BrushParameter.SIZE,
                    outputMin = 0.05f, outputMax = 0.18f, curve = curve(0f, 0f, 0.7f, 0.3f, 1f, 1f),
                ),
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SCATTER, outputMin = 0.6f, outputMax = 1f),
            ),
        ),
    )

    /**
     * Ink & Line: committed marks. Everything here is near-hard and full-opacity; character comes
     * from how width answers pressure, speed and the pen's angle, not from softness.
     */
    val inkAndLine: List<AzphaltBrush> = listOf(
        // A flexible brush-pen: hairline at a whisper, full belly at a press (eased curve so the
        // middle of the pressure range stays controllable), thinning on fast flicks, and a short
        // entry taper so lines start sharp instead of round.
        AzphaltBrush(
            name = "Brush Pen",
            hardness = 0.95f,
            opacity = 1f,
            spacing = 0.04f,
            taper = BrushTaper(startLengthPx = 24f, minSize = 0.15f, minOpacity = 1f),
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE,
                    outputMin = 0.08f, outputMax = 1f, curve = curve(0f, 0f, 0.5f, 0.4f, 1f, 1f),
                ),
                BrushSensorBinding(
                    sensor = BrushSensor.SPEED, parameter = BrushParameter.SIZE,
                    inputMax = 3f, outputMin = 0.7f, outputMax = 1f, invert = true,
                ),
            ),
        ),
        // Technical pen: width barely moves. The point of it is that it doesn't.
        AzphaltBrush(
            name = "Fineliner",
            hardness = 1f,
            opacity = 1f,
            spacing = 0.04f,
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.85f, outputMax = 1f),
            ),
        ),
        // Broad italic nib held at 45 degrees: thick on the down-left diagonal, hairline across
        // it, whatever direction the hand travels. Fixed angle, not followStroke -- a nib does not
        // turn with the stroke, which is the whole calligraphic effect. Spacing is measured on the
        // narrow axis so thin strokes stay continuous.
        AzphaltBrush(
            name = "Italic Nib",
            hardness = 0.95f,
            opacity = 1f,
            spacing = 0.03f,
            isotropicSpacing = false,
            tipRatio = 0.16f,
            angle = 45f,
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.55f, outputMax = 1f),
            ),
        ),
        // Alcohol chisel marker: a flat felt face that leans with the stylus (tilt/azimuth drive
        // the chisel's presentation through the contact model). Translucent and max-combined
        // within a stroke so one pass is even, while crossing strokes darken like real marker.
        AzphaltBrush(
            name = "Chisel Marker",
            hardness = 0.9f,
            opacity = 0.7f,
            spacing = 0.03f,
            isotropicSpacing = false,
            contact = BrushContactConfig(
                enabled = true,
                stiffness = 0.85f,
                drag = 0.2f,
                tiltCoupling = 0.6f,
                orientationCoupling = 0.6f,
                pressureCoupling = 0.2f,
                tipGeometry = BrushTipGeometryConfig(kind = BrushTipKind.CHISEL, chiselAspect = 0.3f),
            ),
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.SPEED, parameter = BrushParameter.OPACITY,
                    inputMax = 3f, outputMin = 0.75f, outputMax = 1f, invert = true,
                ),
            ),
        ),
        // A nearly spent pen dragged quickly: speed starves it, the paper's tooth breaks it up, and
        // per-dab flicker keeps the dryness from looking like a gradient.
        AzphaltBrush(
            name = "Dry Ink",
            hardness = 0.8f,
            opacity = 0.95f,
            spacing = 0.06f,
            scatter = 0.03f,
            substrateResponse = 0.7f,
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.4f, outputMax = 1f),
                BrushSensorBinding(
                    sensor = BrushSensor.SPEED, parameter = BrushParameter.OPACITY,
                    inputMax = 2.5f, outputMin = 0.35f, outputMax = 1f, invert = true,
                ),
                BrushSensorBinding(sensor = BrushSensor.RANDOM_DAB, parameter = BrushParameter.OPACITY, outputMin = 0.55f, outputMax = 1f),
            ),
        ),
    )

    /**
     * Sketch & Dry: pigment scraped onto tooth. Every brush here leans hard on
     * [AzphaltBrush.substrateResponse], so the project's surface shows through -- on a smooth
     * substrate they read clean, on paper or wall they break up the way dry media does.
     */
    val sketchAndDry: List<AzphaltBrush> = listOf(
        // Graphite: a point when upright, the side of the lead when laid over (PENCIL tip geometry
        // recruits side coverage with lean). Pressure darkens before it widens, and side shading
        // is lighter than the point, as it is with a real pencil.
        AzphaltBrush(
            name = "Graphite",
            hardness = 0.7f,
            opacity = 0.65f,
            opacityJitter = 0.15f,
            spacing = 0.08f,
            substrateResponse = 0.85f,
            contact = BrushContactConfig(
                enabled = true,
                stiffness = 0.9f,
                drag = 0.1f,
                tiltCoupling = 0.85f,
                orientationCoupling = 0.7f,
                pressureCoupling = 0.15f,
                tipGeometry = BrushTipGeometryConfig(kind = BrushTipKind.PENCIL, pencilSideCoverage = 1.2f),
            ),
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE, parameter = BrushParameter.OPACITY,
                    outputMin = 0.15f, outputMax = 1f, curve = curve(0f, 0f, 0.6f, 0.45f, 1f, 1f),
                ),
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.75f, outputMax = 1f),
                BrushSensorBinding(
                    sensor = BrushSensor.TILT, parameter = BrushParameter.OPACITY,
                    inputMax = 1.2f, outputMin = 0.55f, outputMax = 1f, invert = true,
                ),
            ),
        ),
        // Vine charcoal: a ragged oval stick tumbling at random angles, building up over itself
        // and catching every bit of tooth.
        AzphaltBrush(
            name = "Charcoal",
            hardness = 0.45f,
            opacity = 0.8f,
            spacing = 0.07f,
            tipRatio = 0.7f,
            sizeJitter = 0.15f,
            opacityJitter = 0.3f,
            scatter = 0.06f,
            buildUp = true,
            substrateResponse = 1f,
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.RANDOM_DAB, parameter = BrushParameter.ROTATION, outputMin = 0f, outputMax = 360f),
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.OPACITY, outputMin = 0.3f, outputMax = 1f),
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.6f, outputMax = 1f),
            ),
        ),
        // Sidewalk chalk: chunky, crumbly and doubled (two scattered dabs per placement), skipping
        // over the surface when dragged fast.
        AzphaltBrush(
            name = "Chalk",
            hardness = 0.6f,
            opacity = 0.9f,
            spacing = 0.1f,
            count = 2,
            scatter = 0.12f,
            sizeJitter = 0.25f,
            opacityJitter = 0.35f,
            substrateResponse = 1f,
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.RANDOM_DAB, parameter = BrushParameter.ROTATION, outputMin = 0f, outputMax = 360f),
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.5f, outputMax = 1f),
                BrushSensorBinding(
                    sensor = BrushSensor.SPEED, parameter = BrushParameter.OPACITY,
                    inputMax = 2.5f, outputMin = 0.6f, outputMax = 1f, invert = true,
                ),
            ),
        ),
        // Conté stick: a squared bar dragged edge-first (follows the stroke). Laying the stylus
        // over turns the narrow edge into the broad face (TILT -> TIP_RATIO).
        AzphaltBrush(
            name = "Conté",
            hardness = 0.75f,
            opacity = 0.85f,
            spacing = 0.05f,
            isotropicSpacing = false,
            tipRatio = 0.4f,
            followStroke = true,
            substrateResponse = 0.9f,
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.OPACITY, outputMin = 0.35f, outputMax = 1f),
                BrushSensorBinding(
                    sensor = BrushSensor.TILT, parameter = BrushParameter.TIP_RATIO,
                    inputMax = 1.2f, outputMin = 1f, outputMax = 2.4f,
                ),
            ),
        ),
    )

    /**
     * Paint: loaded hair and knives. The bristle brushes run the physical contact model (a stable
     * population of constant-diameter hairs grouped into mechanical tufts that bend, splay and
     * lag), so the stroke's texture comes from the tool's mechanics rather than from a bitmap.
     */
    val paint: List<AzphaltBrush> = listOf(
        // Square-ended flat: crisp edges, visible hair tracks, splays under pressure.
        AzphaltBrush(
            name = "Flat Bristle",
            hardness = 0.75f,
            opacity = 1f,
            spacing = 0.04f,
            followStroke = true,
            substrateResponse = 0.4f,
            contact = bristleContact(BrushMorphology.FLAT, stiffness = 0.6f, drag = 0.6f),
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.6f, outputMax = 1f),
            ),
        ),
        // Filbert: the rounded flat. Softer hair, more lag, and gentle build-up so it blends.
        AzphaltBrush(
            name = "Filbert",
            hardness = 0.55f,
            opacity = 0.95f,
            spacing = 0.04f,
            followStroke = true,
            buildUp = true,
            substrateResponse = 0.3f,
            contact = bristleContact(BrushMorphology.FILBERT, stiffness = 0.45f, drag = 0.7f),
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.55f, outputMax = 1f),
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.OPACITY, outputMin = 0.6f, outputMax = 1f),
            ),
        ),
        // Dry brush: coarse, sparsely packed hair with little paint on it. Only the tops of the
        // tooth catch, and speed or a light touch starves it further.
        AzphaltBrush(
            name = "Dry Brush",
            hardness = 0.8f,
            opacity = 0.9f,
            spacing = 0.05f,
            followStroke = true,
            substrateResponse = 1f,
            contact = bristleContact(
                BrushMorphology.FLAT, stiffness = 0.7f, drag = 0.5f,
                bristleDiameterPx = 1.6f, packingFraction = 0.45f,
            ),
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.OPACITY, outputMin = 0.35f, outputMax = 1f),
                BrushSensorBinding(
                    sensor = BrushSensor.SPEED, parameter = BrushParameter.OPACITY,
                    inputMax = 2.5f, outputMin = 0.4f, outputMax = 1f, invert = true,
                ),
            ),
        ),
        // Palette knife: a thin blade laid flat, dragged edge-on, leaving real paint thickness
        // (Impasto v2: stiff, yielding, slow to level, glossy while wet).
        AzphaltBrush(
            name = "Palette Knife",
            hardness = 0.95f,
            opacity = 1f,
            spacing = 0.03f,
            isotropicSpacing = false,
            tipRatio = 0.22f,
            followStroke = true,
            impastoThicknessRate = 0.6f,
            impastoMaterial = ImpastoMaterialConfig(
                version = 2,
                enabled = true,
                initialLoad = 1f,
                wetness = 0.6f,
                pickupRate = 0.3f,
                levelingRate = 0.1f,
                viscosity = 0.8f,
                yieldLikeStrength = 0.7f,
                substrateResponse = 0.3f,
                baseRoughness = 0.6f,
                wetSpecularStrength = 0.5f,
            ),
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.7f, outputMax = 1f),
            ),
        ),
        // Wash: a big soft mop of thin colour. Glazes stack; resting pools it.
        AzphaltBrush(
            name = "Wash",
            hardness = 0f,
            opacity = 0.25f,
            spacing = 0.08f,
            sizeJitter = 0.05f,
            buildUp = true,
            airbrushDabsPerSecond = 6f,
            airbrushStillnessRadiusPx = 6f,
            substrateResponse = 0.15f,
            dynamics = listOf(
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.OPACITY, outputMin = 0.3f, outputMax = 1f),
                BrushSensorBinding(sensor = BrushSensor.PRESSURE, parameter = BrushParameter.SIZE, outputMin = 0.7f, outputMax = 1f),
            ),
        ),
    )

    /** Every bundled brush, grouped for the Brushes carousel and the desktop rail. [basics] first. */
    val sets: List<BuiltInBrushSet> = listOf(
        BuiltInBrushSet("Basics", basics),
        BuiltInBrushSet("Aerosol", aerosol),
        BuiltInBrushSet("Ink & Line", inkAndLine),
        BuiltInBrushSet("Sketch & Dry", sketchAndDry),
        BuiltInBrushSet("Paint", paint),
    )

    /** [sets], flattened in order. [round] stays first: it is the default everywhere. */
    val presets: List<AzphaltBrush> = sets.flatMap { it.brushes }

    /** The set a bundled brush belongs to, by name, or null for a brush that isn't bundled. */
    fun setOf(brushName: String): BuiltInBrushSet? = sets.firstOrNull { set -> set.brushes.any { it.name == brushName } }

    private fun curve(vararg xy: Float): BrushResponseCurve =
        BrushResponseCurve(xy.toList().chunked(2) { (x, y) -> BrushCurvePoint(x, y) })

    private fun bristleContact(
        morphology: BrushMorphology,
        stiffness: Float,
        drag: Float,
        bristleDiameterPx: Float = 1.15f,
        packingFraction: Float = 0.72f,
    ) = BrushContactConfig(
        enabled = true,
        stiffness = stiffness,
        drag = drag,
        tufts = BrushTuftConfig(enabled = true, morphology = morphology),
        tipGeometry = BrushTipGeometryConfig(
            kind = BrushTipKind.BRISTLE,
            population = BrushBristlePopulationConfig(
                enabled = true,
                bristleDiameterPx = bristleDiameterPx,
                packingFraction = packingFraction,
            ),
        ),
    )
}

/** A named group of bundled brushes. [name] is what the carousel's set chips and the desktop rail show. */
data class BuiltInBrushSet(val name: String, val brushes: List<AzphaltBrush>)
