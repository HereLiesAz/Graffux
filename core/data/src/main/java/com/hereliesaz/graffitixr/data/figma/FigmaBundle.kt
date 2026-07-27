package com.hereliesaz.graffitixr.data.figma

import androidx.compose.ui.graphics.BlendMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The interchange format the Graffux → Figma companion plugin reads.
 *
 * Figma's REST API cannot create design content, so pushing artwork the other way has to go through
 * a plugin running inside Figma. This is the payload they agree on: a single JSON file the user
 * moves from their device to their computer by whatever means they already use, and opens with the
 * plugin.
 *
 * PNGs are base64 inline rather than a zip so the plugin needs nothing but `JSON.parse` and a base64
 * decode — no bundled archive library inside Figma's sandbox. The ~33% size cost buys a plugin with
 * no build step and no dependencies.
 *
 * Each layer's image is pre-composited at full document size with its own opacity, blend, and clip
 * neutralised, so geometry is baked in (every layer is a full-bleed image at the origin) while
 * opacity and blend remain live, editable Figma properties rather than burnt into pixels.
 */
@Serializable
data class FigmaBundle(
    val version: Int = FORMAT_VERSION,
    val name: String,
    val documentWidth: Int,
    val documentHeight: Int,
    val layers: List<FigmaBundleLayer>,
) {
    companion object {
        const val FORMAT_VERSION = 1

        /** Suffix used for the written file, so the plugin's file picker can filter for it. */
        const val FILE_SUFFIX = ".graffux-figma.json"

        /** Defaults are written explicitly so the plugin never has to infer a missing field. */
        private val json = Json { encodeDefaults = true }

        /**
         * Encodes a bundle for the plugin. Lives here rather than at the call site because only this
         * module carries kotlinx-serialization — the editor builds the bundle and hands it over.
         */
        fun encode(bundle: FigmaBundle): String = json.encodeToString(serializer(), bundle)

        fun decode(text: String): FigmaBundle = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class FigmaBundleLayer(
    val name: String,
    /** Base64 PNG, document-sized, with opacity/blend/clip neutralised — see [FigmaBundle]. */
    val pngBase64: String,
    val opacity: Float = 1f,
    /** A Figma `BlendMode` string; see [figmaBlendMode]. */
    val blendMode: String = "NORMAL",
    val visible: Boolean = true,
)

/**
 * Maps a layer's blend mode to Figma's equivalent. Takes Compose's [BlendMode] because that is what
 * `Layer.blendMode` actually carries.
 *
 * The separable and non-separable modes line up one-to-one. The Porter-Duff compositing operators
 * ([BlendMode.SrcIn] and friends) have no Figma counterpart at all, so they degrade to NORMAL — the
 * layer still arrives with the right pixels, it just won't re-composite the same way if the user
 * rearranges it in Figma.
 */
fun figmaBlendMode(mode: BlendMode): String = when (mode) {
    BlendMode.Multiply -> "MULTIPLY"
    BlendMode.Screen -> "SCREEN"
    BlendMode.Overlay -> "OVERLAY"
    BlendMode.Darken -> "DARKEN"
    BlendMode.Lighten -> "LIGHTEN"
    BlendMode.ColorDodge -> "COLOR_DODGE"
    BlendMode.ColorBurn -> "COLOR_BURN"
    BlendMode.Hardlight -> "HARD_LIGHT"
    BlendMode.Softlight -> "SOFT_LIGHT"
    BlendMode.Difference -> "DIFFERENCE"
    BlendMode.Exclusion -> "EXCLUSION"
    BlendMode.Hue -> "HUE"
    BlendMode.Saturation -> "SATURATION"
    BlendMode.Color -> "COLOR"
    BlendMode.Luminosity -> "LUMINOSITY"
    else -> "NORMAL"
}
