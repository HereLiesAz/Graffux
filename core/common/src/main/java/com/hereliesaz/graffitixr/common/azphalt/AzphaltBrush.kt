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
    val scatter: Float = 0f,
    val angle: Float = 0f,
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
        grainScale = grainScale.coerceIn(0.05f, 16f),
        grainStrength = grainStrength.coerceIn(0f, 1f),
        colorMix = colorMix.coerceIn(0f, 1f),
        maskedBrush = maskedBrush?.sanitized(),
        dynamics = dynamics.map(BrushSensorBinding::sanitized),
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
                angle = f("angle") ?: 0f,
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
            ).sanitized()
        }
    }
}
