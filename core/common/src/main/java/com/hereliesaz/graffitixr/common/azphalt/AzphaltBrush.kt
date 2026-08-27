package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.floatOrNull

/**
 * A normalized stamp-brush definition parsed from an azphalt `brush` asset's `params`
 * (spec/extension-manifest.md § assets). GraffitiXR is an asset host, so a brush extension contributes
 * *data* — a stamp shape plus stroke dynamics — that the editor renders; it carries no code.
 *
 * The model follows how Procreate/Krita-class raster painters build a stroke: a **stamp** (the brush's
 * shape, optionally a grain-textured image) is laid down repeatedly along the stroke path. Static
 * values below describe the brush's baseline; [dynamics] is the sensor/curve routing layer that can
 * drive those values from pressure, speed, tilt, orientation, travelled distance, time and stable
 * random signals without teaching the renderer about any particular input device.
 *
 * Pure and Android-free so parsing and the placement maths are unit-testable; an Android renderer that
 * rasterizes each dab lives in the editor layer.
 */
@Serializable
data class AzphaltBrush(
    /** Human name for the brush (from the manifest asset, falling back to the package name). */
    val name: String,
    /**
     * Dab spacing as a fraction of the tip diameter (Procreate's "Spacing"). `0.05` = tight/solid,
     * `1.0` = dabs just touching, `>1` = a dotted trail. Clamped to `0.01..4`.
     */
    val spacing: Float = 0.1f,
    /** Per-dab alpha (`0..1`), multiplied into the brush colour. `1` = fully opaque tip. */
    val opacity: Float = 1f,
    /**
     * Edge falloff (`0..1`): `1` = a crisp disc, `0` = a soft feathered blob. Maps to the radius at
     * which the round stamp's alpha starts ramping to zero (`hardness` of the way out).
     */
    val hardness: Float = 1f,
    /** Random size variation per dab (`0..1`): fraction of diameter each dab may shrink by. */
    val sizeJitter: Float = 0f,
    /** Random alpha variation per dab (`0..1`): fraction of [opacity] each dab may drop by. */
    val opacityJitter: Float = 0f,
    /** Perpendicular scatter per dab, as a fraction of diameter (`0..`): Procreate's "Scatter". */
    val scatter: Float = 0f,
    /** Base stamp rotation in degrees; meaningful for a non-round [shapePath] stamp. */
    val angle: Float = 0f,
    /**
     * In-package path to a greyscale stamp image (the brush "Shape"), relative to `/assets`. Null ⇒ a
     * generated round tip governed by [hardness]. A host that can't load the image falls back to round.
     */
    val shapePath: String? = null,
    /**
     * In-package path to a tiling grain texture (the brush "Grain") modulating each dab's alpha. Null ⇒
     * a smooth dab. Optional for a host to honour.
     */
    val grainPath: String? = null,
    /** Whether the stamp rotates to follow the stroke direction (Procreate's "Azimuth"/heading). */
    val followStroke: Boolean = false,
    /**
     * Krita-style sensor routes. Empty by default, which is an important compatibility invariant:
     * every existing brush renders exactly as before until the author explicitly maps a sensor.
     */
    val dynamics: List<BrushSensorBinding> = emptyList(),
) {
    /**
     * Clamps every value into the same ranges [fromParams] enforces. A brush built in the app's own
     * Brush Studio never goes through the params parser, so this keeps the one set of legal ranges
     * shared between the two construction paths instead of letting them drift.
     */
    fun sanitized(): AzphaltBrush = copy(
        name = name.trim().ifBlank { "Custom Brush" },
        spacing = spacing.coerceIn(0.01f, 4f),
        opacity = opacity.coerceIn(0f, 1f),
        hardness = hardness.coerceIn(0f, 1f),
        sizeJitter = sizeJitter.coerceIn(0f, 1f),
        opacityJitter = opacityJitter.coerceIn(0f, 1f),
        scatter = scatter.coerceAtLeast(0f),
        dynamics = dynamics.map(BrushSensorBinding::sanitized),
    )

    companion object {
        /**
         * Parse a `brush` asset's declarative [params] into an [AzphaltBrush], applying defaults and
         * clamping every value to a sane range. Unknown keys are ignored and any malformed value falls
         * back to its default, mirroring [ExtensionRepository]'s lenient LUT-param reading — a brush
         * from a newer/looser package still yields a usable tip rather than throwing.
         *
         * `dynamics` is an optional array of [BrushSensorBinding] objects. It uses the stable wire names
         * from [BrushSensor]/[BrushParameter] (`pressure`, `speed`, `tilt`, `size`, `opacity`, etc.). A
         * malformed individual binding is ignored instead of invalidating the whole brush package.
         */
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
            return AzphaltBrush(
                name = name,
                spacing = (f("spacing") ?: 0.1f).coerceIn(0.01f, 4f),
                opacity = (f("opacity") ?: 1f).coerceIn(0f, 1f),
                hardness = (f("hardness") ?: 1f).coerceIn(0f, 1f),
                sizeJitter = (f("sizeJitter") ?: 0f).coerceIn(0f, 1f),
                opacityJitter = (f("opacityJitter") ?: 0f).coerceIn(0f, 1f),
                scatter = (f("scatter") ?: 0f).coerceAtLeast(0f),
                angle = f("angle") ?: 0f,
                shapePath = s("shape") ?: s("shapePath"),
                grainPath = s("grain") ?: s("grainPath"),
                followStroke = b("followStroke") ?: false,
                dynamics = dynamics,
            )
        }
    }
}
