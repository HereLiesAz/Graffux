package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.floatOrNull

@Serializable
enum class GrainBehavior {
    @SerialName("moving") MOVING,
    @SerialName("canvas") CANVAS_LOCKED,
}

@Serializable
enum class GrainBlendMode {
    @SerialName("multiply") MULTIPLY,
    @SerialName("subtract") SUBTRACT,
    @SerialName("darken") DARKEN,
    @SerialName("overlay") OVERLAY,
}

@Serializable
enum class BrushColorSource {
    @SerialName("plain") PLAIN,
    @SerialName("gradient") GRADIENT,
    @SerialName("uniformRandom") UNIFORM_RANDOM,
}

@Serializable
enum class MaskedBrushBlendMode {
    @SerialName("multiply") MULTIPLY,
    @SerialName("subtract") SUBTRACT,
}

/**
 * Start/end stroke taper. Distances are measured in canvas pixels along the stroke path from
 * whichever end is closer; a dab inside both zones uses the smaller (more tapered) factor.
 *
 * [liftOffSynthesizesPressure] is the phone-first addition Krita has no direct equivalent for:
 * finger input usually reports a constant/absent pressure axis, so a fixed-distance end taper
 * looks the same whether the stroke was yanked away or lifted gently. When enabled, the end
 * taper is additionally scaled by how slow the recorded speed was at each dab relative to the
 * stroke's peak speed, so a slow, deliberate lift fades out and a fast one stays closer to full
 * size/opacity until it is cut off — approximating what a real pressure sensor would have done.
 */
@Serializable
data class BrushTaper(
    val startLengthPx: Float = 0f,
    val endLengthPx: Float = 0f,
    /** Size/opacity multiplier at the very start/end of a taper zone. 1 disables that taper. */
    val minSize: Float = 0f,
    val minOpacity: Float = 0f,
    val liftOffSynthesizesPressure: Boolean = false,
) {
    fun sanitized(): BrushTaper = copy(
        startLengthPx = startLengthPx.coerceAtLeast(0f),
        endLengthPx = endLengthPx.coerceAtLeast(0f),
        minSize = minSize.coerceIn(0f, 1f),
        minOpacity = minOpacity.coerceIn(0f, 1f),
    )

    fun isActive(): Boolean = startLengthPx > 0f || endLengthPx > 0f
}

@Serializable
data class MaskedBrushConfig(
    val shapePath: String? = null,
    val sizeRatio: Float = 1f,
    /** Height / width. 1 = round/square; values below 1 produce an elongated tip. */
    val tipRatio: Float = 1f,
    val hardness: Float = 1f,
    val opacity: Float = 1f,
    val flow: Float = 1f,
    val angle: Float = 0f,
    val followStroke: Boolean = false,
    val scatter: Float = 0f,
    /** Along-heading scatter, mirroring [AzphaltBrush.scatterLongitudinal] for the secondary tip. */
    val scatterLongitudinal: Float = 0f,
    /** Degrees of extra rotation per pixel of cumulative stroke distance, mirroring
     * [AzphaltBrush.rotationPerPx] for the secondary tip. */
    val rotationPerPx: Float = 0f,
    val invert: Boolean = false,
    val blendMode: MaskedBrushBlendMode = MaskedBrushBlendMode.MULTIPLY,
    val dynamics: List<BrushSensorBinding> = emptyList(),
) {
    fun sanitized(): MaskedBrushConfig = copy(
        sizeRatio = sizeRatio.coerceIn(0.05f, 8f),
        tipRatio = tipRatio.coerceIn(0.05f, 1f),
        hardness = hardness.coerceIn(0f, 1f),
        opacity = opacity.coerceIn(0f, 1f),
        flow = flow.coerceIn(0f, 1f),
        scatter = scatter.coerceAtLeast(0f),
        scatterLongitudinal = scatterLongitudinal.coerceAtLeast(0f),
        dynamics = dynamics.map(BrushSensorBinding::sanitized),
    )
}

/**
 * A normalized stamp-brush definition. Graffux follows Krita's stage decomposition: spacing,
 * primary brush-tip mask, sensor options, texture/grain, and an optional masked second tip are
 * independent systems rather than one pre-baked stamp bitmap.
 */
@Serializable
data class AzphaltBrush(
    val name: String,
    val spacing: Float = 0.1f,
    /** True keeps historical diameter-only spacing; false makes spacing ratio-aware. */
    val isotropicSpacing: Boolean = true,
    /** Height / width of the primary tip. */
    val tipRatio: Float = 1f,
    val opacity: Float = 1f,
    val hardness: Float = 1f,
    val sizeJitter: Float = 0f,
    val opacityJitter: Float = 0f,
    /** Perpendicular-to-heading scatter, as a fraction of the resolved diameter. */
    val scatter: Float = 0f,
    /** Along-heading scatter, as a fraction of the resolved diameter. Independent random stream. */
    val scatterLongitudinal: Float = 0f,
    val angle: Float = 0f,
    /** Degrees of extra rotation per pixel of cumulative stroke distance (Krita's Distance rotation
     * sensor). Additive with [followStroke]'s heading-based rotation and any ROTATION sensor route. */
    val rotationPerPx: Float = 0f,
    val shapePath: String? = null,
    val grainPath: String? = null,
    val grainScale: Float = 1f,
    val grainStrength: Float = 1f,
    val grainBehavior: GrainBehavior = GrainBehavior.MOVING,
    val grainBlendMode: GrainBlendMode = GrainBlendMode.MULTIPLY,
    val grainRandomOffsetPerStroke: Boolean = false,
    val grainOffsetX: Float = 0f,
    val grainOffsetY: Float = 0f,
    val followStroke: Boolean = false,
    /** Krita-style colour source. PLAIN is the historical single foreground colour. */
    val colorSource: BrushColorSource = BrushColorSource.PLAIN,
    /** Base foreground→background gradient coordinate. A MIX sensor route may override per dab. */
    val colorMix: Float = 0f,
    val maskedBrush: MaskedBrushConfig? = null,
    val dynamics: List<BrushSensorBinding> = emptyList(),
    /** Default disables both zones, so existing brushes render exactly as before. */
    val taper: BrushTaper = BrushTaper(),
    /** Airbrush build-up (roadmap item 13, [AirbrushEngine.heldDabs]): additional dabs deposited
     *  at this cadence while the pointer is held roughly still. 0 (the default) disables it
     *  entirely, matching [AirbrushEngine.heldDabs]'s own "non-positive disables" contract, so
     *  existing brushes are unaffected. Only takes effect on stroke commit/replay, not the live
     *  preview -- see the call site in `DrawingEngine.kt` for why. */
    val airbrushDabsPerSecond: Float = 0f,
    /** A sample within this radius of the current held run's anchor still counts as "held";
     *  movement past it resets the run. Irrelevant while [airbrushDabsPerSecond] is 0. */
    val airbrushStillnessRadiusPx: Float = 3f,
    /** Paint-thickness build-up (roadmap item 12, `ImpastoEngine.deposit`): how much each dab
     *  raises the layer's height map, in the same units as `ImpastoEngine`'s `thicknessRate`.
     *  0 (the default) disables it entirely, matching `ImpastoEngine.deposit`'s own
     *  "non-positive rate is a no-op" contract, so existing brushes are unaffected. Only takes
     *  effect on stroke commit/replay, not the live preview -- see the call site in
     *  `DrawingEngine.kt` for why (same reasoning as [airbrushDabsPerSecond]). */
    val impastoThicknessRate: Float = 0f,
) {
    fun sanitized(): AzphaltBrush = copy(
        name = name.trim().ifBlank { "Custom Brush" },
        spacing = spacing.coerceIn(0.01f, 4f),
        tipRatio = tipRatio.coerceIn(0.05f, 1f),
        opacity = opacity.coerceIn(0f, 1f),
        hardness = hardness.coerceIn(0f, 1f),
        sizeJitter = sizeJitter.coerceIn(0f, 1f),
        opacityJitter = opacityJitter.coerceIn(0f, 1f),
        scatter = scatter.coerceAtLeast(0f),
        scatterLongitudinal = scatterLongitudinal.coerceAtLeast(0f),
        grainScale = grainScale.coerceIn(0.05f, 16f),
        grainStrength = grainStrength.coerceIn(0f, 1f),
        colorMix = colorMix.coerceIn(0f, 1f),
        maskedBrush = maskedBrush?.sanitized(),
        dynamics = dynamics.map(BrushSensorBinding::sanitized),
        taper = taper.sanitized(),
        airbrushDabsPerSecond = airbrushDabsPerSecond.coerceAtLeast(0f),
        airbrushStillnessRadiusPx = airbrushStillnessRadiusPx.coerceAtLeast(0f),
        impastoThicknessRate = impastoThicknessRate.coerceAtLeast(0f),
    )

    fun spacingReferencePx(diameterPx: Float): Float =
        if (isotropicSpacing) diameterPx else diameterPx * tipRatio.coerceIn(0.05f, 1f)

    companion object {
        fun fromParams(name: String, params: JsonObject?): AzphaltBrush {
            fun f(key: String): Float? = (params?.get(key) as? JsonPrimitive)?.floatOrNull
            fun s(key: String): String? =
                (params?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun b(key: String): Boolean? = (params?.get(key) as? JsonPrimitive)?.booleanOrNull

            val dynamics = (params?.get("dynamics") as? JsonArray)
                ?.mapNotNull { element ->
                    runCatching { AzphaltJson.decodeFromJsonElement<BrushSensorBinding>(element) }.getOrNull()
                }
                .orEmpty()
                .map(BrushSensorBinding::sanitized)
            val grainBehavior = params?.get("grainBehavior")?.let { element ->
                runCatching { AzphaltJson.decodeFromJsonElement<GrainBehavior>(element) }.getOrNull()
            } ?: GrainBehavior.MOVING
            val grainBlendMode = params?.get("grainBlendMode")?.let { element ->
                runCatching { AzphaltJson.decodeFromJsonElement<GrainBlendMode>(element) }.getOrNull()
            } ?: GrainBlendMode.MULTIPLY
            val maskedBrush = params?.get("maskedBrush")?.let { element ->
                runCatching { AzphaltJson.decodeFromJsonElement<MaskedBrushConfig>(element) }.getOrNull()
            }?.sanitized()
            val colorSource = params?.get("colorSource")?.let { element ->
                runCatching { AzphaltJson.decodeFromJsonElement<BrushColorSource>(element) }.getOrNull()
            } ?: BrushColorSource.PLAIN
            val taper = params?.get("taper")?.let { element ->
                runCatching { AzphaltJson.decodeFromJsonElement<BrushTaper>(element) }.getOrNull()
            }?.sanitized() ?: BrushTaper()

            return AzphaltBrush(
                name = name,
                spacing = (f("spacing") ?: 0.1f).coerceIn(0.01f, 4f),
                isotropicSpacing = b("isotropicSpacing") ?: true,
                tipRatio = (f("ratio") ?: f("tipRatio") ?: 1f).coerceIn(0.05f, 1f),
                opacity = (f("opacity") ?: 1f).coerceIn(0f, 1f),
                hardness = (f("hardness") ?: 1f).coerceIn(0f, 1f),
                sizeJitter = (f("sizeJitter") ?: 0f).coerceIn(0f, 1f),
                opacityJitter = (f("opacityJitter") ?: 0f).coerceIn(0f, 1f),
                scatter = (f("scatter") ?: 0f).coerceAtLeast(0f),
                scatterLongitudinal = (f("scatterLongitudinal") ?: 0f).coerceAtLeast(0f),
                angle = f("angle") ?: 0f,
                rotationPerPx = f("rotationPerPx") ?: 0f,
                shapePath = s("shape") ?: s("shapePath"),
                grainPath = s("grain") ?: s("grainPath"),
                grainScale = (f("grainScale") ?: 1f).coerceIn(0.05f, 16f),
                grainStrength = (f("grainStrength") ?: 1f).coerceIn(0f, 1f),
                grainBehavior = grainBehavior,
                grainBlendMode = grainBlendMode,
                grainRandomOffsetPerStroke = b("grainRandomOffsetPerStroke") ?: false,
                grainOffsetX = f("grainOffsetX") ?: 0f,
                grainOffsetY = f("grainOffsetY") ?: 0f,
                followStroke = b("followStroke") ?: false,
                colorSource = colorSource,
                colorMix = (f("colorMix") ?: f("mix") ?: 0f).coerceIn(0f, 1f),
                maskedBrush = maskedBrush,
                dynamics = dynamics,
                taper = taper,
                airbrushDabsPerSecond = (f("airbrushDabsPerSecond") ?: 0f).coerceAtLeast(0f),
                airbrushStillnessRadiusPx = (f("airbrushStillnessRadiusPx") ?: 3f).coerceAtLeast(0f),
                impastoThicknessRate = (f("impastoThicknessRate") ?: 0f).coerceAtLeast(0f),
            ).sanitized()
        }
    }
}
