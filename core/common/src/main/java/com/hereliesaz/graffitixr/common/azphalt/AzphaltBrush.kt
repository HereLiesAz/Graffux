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

/** How the grain coordinate system follows a stroke. */
@Serializable
enum class GrainBehavior {
    /** Pattern follows every dab, like texture painted on the brush tip. */
    @SerialName("moving") MOVING,
    /** Pattern is fixed to canvas coordinates, so the brush reveals a stationary surface texture. */
    @SerialName("canvas") CANVAS_LOCKED,
}

/** Alpha-combination modes modelled after Krita's texture stage. */
@Serializable
enum class GrainBlendMode {
    @SerialName("multiply") MULTIPLY,
    @SerialName("subtract") SUBTRACT,
    @SerialName("darken") DARKEN,
    @SerialName("overlay") OVERLAY,
}

/** How a secondary/masked tip combines with the primary tip. */
@Serializable
enum class MaskedBrushBlendMode {
    @SerialName("multiply") MULTIPLY,
    @SerialName("subtract") SUBTRACT,
}

/**
 * Krita-style second brush tip. It is resolved independently from the primary tip, then combined as
 * a live mask for each primary dab. Size remains relative to the primary brush size, matching Krita's
 * masked-brush semantics, while rotation/scatter/opacity/flow may have their own sensor routes.
 */
@Serializable
data class MaskedBrushConfig(
    val shapePath: String? = null,
    val sizeRatio: Float = 1f,
    /** Height / width. 1 = round/square; values below 1 produce an elongated tip. */
    val tipRatio: Float = 1f,
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
        opacity = opacity.coerceIn(0f, 1f),
        flow = flow.coerceIn(0f, 1f),
        scatter = scatter.coerceAtLeast(0f),
        dynamics = dynamics.map(BrushSensorBinding::sanitized),
    )
}

/**
 * A normalized stamp-brush definition parsed from an azphalt `brush` asset's `params`.
 *
 * Graffux follows the same high-level decomposition as Krita's Pixel Brush Engine: path spacing,
 * primary brush-tip mask, sensor options, texture/grain, and an optional masked second tip are
 * independent stages. Keeping those stages separate is what lets a canvas-locked texture stay still
 * while a masked second tip follows/scatters with the stroke.
 */
@Serializable
data class AzphaltBrush(
    val name: String,
    /** Dab spacing as a fraction of the tip spacing reference dimension. */
    val spacing: Float = 0.1f,
    /**
     * Krita's isotropic-spacing switch. True preserves Graffux's historical diameter-only spacing.
     * False makes spacing ratio-aware, so a narrow/elongated tip places impressions more densely.
     */
    val isotropicSpacing: Boolean = true,
    /** Height / width of the primary tip. 1 = round/square. */
    val tipRatio: Float = 1f,
    val opacity: Float = 1f,
    val hardness: Float = 1f,
    val sizeJitter: Float = 0f,
    val opacityJitter: Float = 0f,
    /** Perpendicular scatter per dab, as a fraction of the current tip diameter. */
    val scatter: Float = 0f,
    val angle: Float = 0f,
    /** In-package path to the primary greyscale/alpha brush-tip image. */
    val shapePath: String? = null,
    /** In-package path to the tiling grain texture. */
    val grainPath: String? = null,
    val grainScale: Float = 1f,
    val grainStrength: Float = 1f,
    val grainBehavior: GrainBehavior = GrainBehavior.MOVING,
    val grainBlendMode: GrainBlendMode = GrainBlendMode.MULTIPLY,
    /** Stable per-stroke grain phase derived from the stroke seed instead of changing per dab. */
    val grainRandomOffsetPerStroke: Boolean = false,
    val grainOffsetX: Float = 0f,
    val grainOffsetY: Float = 0f,
    val followStroke: Boolean = false,
    /** Optional Krita-style secondary tip used as a live mask. */
    val maskedBrush: MaskedBrushConfig? = null,
    /** Krita-style sensor routes for the primary tip. */
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
        maskedBrush = maskedBrush?.sanitized(),
        dynamics = dynamics.map(BrushSensorBinding::sanitized),
    )

    /** Spacing reference for a dab whose resolved diameter is [diameterPx]. */
    fun spacingReferencePx(diameterPx: Float): Float =
        if (isotropicSpacing) diameterPx else diameterPx * tipRatio.coerceIn(0.05f, 1f)

    companion object {
        fun fromParams(name: String, params: JsonObject?): AzphaltBrush {
            fun f(key: String): Float? = (params?.get(key) as? JsonPrimitive)?.floatOrNull
            fun s(key: String): String? =
                (params?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            fun b(key: String): Boolean? = (params?.get(key) as? JsonPrimitive)?.booleanOrNull
            fun <T> decode(key: String): T? = params?.get(key)?.let { element ->
                runCatching { AzphaltJson.decodeFromJsonElement<T>(element) }.getOrNull()
            }

            val dynamics = (params?.get("dynamics") as? JsonArray)
                ?.mapNotNull { element ->
                    runCatching { AzphaltJson.decodeFromJsonElement<BrushSensorBinding>(element) }.getOrNull()
                }
                .orEmpty()
                .map(BrushSensorBinding::sanitized)

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
                grainBehavior = decode<GrainBehavior>("grainBehavior") ?: GrainBehavior.MOVING,
                grainBlendMode = decode<GrainBlendMode>("grainBlendMode") ?: GrainBlendMode.MULTIPLY,
                grainRandomOffsetPerStroke = b("grainRandomOffsetPerStroke") ?: false,
                grainOffsetX = f("grainOffsetX") ?: 0f,
                grainOffsetY = f("grainOffsetY") ?: 0f,
                followStroke = b("followStroke") ?: false,
                maskedBrush = decode<MaskedBrushConfig>("maskedBrush")?.sanitized(),
                dynamics = dynamics,
            ).sanitized()
        }
    }
}
