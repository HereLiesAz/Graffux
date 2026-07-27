package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Figma's shared styles: named colour and text tokens applied *by reference*, so changing the token
 * updates everything using it.
 *
 * Unlike components — which are derived from the layer stack — a style has no layer to live on, so
 * the definitions are a real registry on [EditorUiState]. The reference is the only thing stored on
 * the artwork: a shape keeps `fillStyleId`, and [resolve] writes the token's current value into the
 * concrete field the renderers already read.
 *
 * That "resolve into the existing field" approach is deliberate. Every renderer, exporter, and
 * hit-test keeps reading `fillArgb` exactly as before and needs no knowledge of styles at all — the
 * feature is a pre-pass over the layer list, not a change to how anything draws.
 *
 * A reference to a style that no longer exists is left alone rather than blanked: the artwork keeps
 * whatever value it last resolved to, which is far better than silently turning black.
 */

/** A named colour token. */
@Serializable
data class ColorStyle(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val argb: Long,
)

/**
 * A named text token. Covers the typographic properties of [TextLayerParams] — the ones that make a
 * heading a heading — and deliberately not the text content itself, which is per-layer.
 */
@Serializable
data class TextStyle(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val fontName: String = "Roboto",
    val fontSizeDp: Float = 150f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val letterSpacingEm: Float = 0f,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
)

object StyleOps {

    /**
     * Resolves every style reference in [layers] to its token's current value. Idempotent, and a
     * no-op for a document using no styles, so callers run it after any edit rather than working out
     * which edits could have mattered.
     */
    fun resolve(
        layers: List<Layer>,
        colorStyles: List<ColorStyle>,
        textStyles: List<TextStyle>,
    ): List<Layer> {
        if (colorStyles.isEmpty() && textStyles.isEmpty()) return layers
        val colors = colorStyles.associateBy { it.id }
        val texts = textStyles.associateBy { it.id }
        return layers.map { layer ->
            layer.copy(
                shapes = layer.shapes.map { it.resolvedWith(colors) },
                textParams = layer.textParams?.resolvedWith(texts),
            )
        }
    }

    /** Points a shape's fill at a colour token, or clears the link with null. */
    fun setShapeFillStyle(
        layers: List<Layer>,
        layerId: String,
        styleId: String?,
    ): List<Layer> = layers.map { layer ->
        if (layer.id != layerId) layer
        else layer.copy(shapes = layer.shapes.map { it.copy(fillStyleId = styleId) })
    }

    /** Points a shape's stroke at a colour token, or clears the link with null. */
    fun setShapeStrokeStyle(
        layers: List<Layer>,
        layerId: String,
        styleId: String?,
    ): List<Layer> = layers.map { layer ->
        if (layer.id != layerId) layer
        else layer.copy(shapes = layer.shapes.map { it.copy(strokeStyleId = styleId) })
    }

    /** Points a text layer at a text token, or clears the link with null. */
    fun setTextStyle(
        layers: List<Layer>,
        layerId: String,
        styleId: String?,
    ): List<Layer> = layers.map { layer ->
        if (layer.id != layerId || layer.textParams == null) layer
        else layer.copy(textParams = layer.textParams.copy(styleId = styleId))
    }

    /**
     * Drops a colour token and unlinks everything that referenced it. The artwork keeps the value it
     * last resolved to — deleting a token must not change how the document looks, only stop future
     * edits from propagating.
     */
    fun deleteColorStyle(
        layers: List<Layer>,
        styles: List<ColorStyle>,
        styleId: String,
    ): Pair<List<Layer>, List<ColorStyle>> {
        val unlinked = layers.map { layer ->
            layer.copy(
                shapes = layer.shapes.map {
                    it.copy(
                        fillStyleId = it.fillStyleId?.takeIf { id -> id != styleId },
                        strokeStyleId = it.strokeStyleId?.takeIf { id -> id != styleId },
                    )
                },
            )
        }
        return unlinked to styles.filterNot { it.id == styleId }
    }

    /** Drops a text token and unlinks everything that referenced it. See [deleteColorStyle]. */
    fun deleteTextStyle(
        layers: List<Layer>,
        styles: List<TextStyle>,
        styleId: String,
    ): Pair<List<Layer>, List<TextStyle>> {
        val unlinked = layers.map { layer ->
            val params = layer.textParams
            if (params?.styleId != styleId) layer else layer.copy(textParams = params.copy(styleId = null))
        }
        return unlinked to styles.filterNot { it.id == styleId }
    }

    /** Builds a colour token from a shape's current fill — "create style from selection". */
    fun colorStyleFromShape(shape: VectorShape, name: String): ColorStyle =
        ColorStyle(name = name, argb = shape.fillArgb)

    /** Builds a text token from a text layer's current look. */
    fun textStyleFromParams(params: TextLayerParams, name: String): TextStyle = TextStyle(
        name = name,
        fontName = params.fontName,
        fontSizeDp = params.fontSizeDp,
        colorArgb = params.colorArgb,
        letterSpacingEm = params.letterSpacingEm,
        isBold = params.isBold,
        isItalic = params.isItalic,
    )

    private fun VectorShape.resolvedWith(colors: Map<String, ColorStyle>): VectorShape {
        val fill = fillStyleId?.let { colors[it] }
        val stroke = strokeStyleId?.let { colors[it] }
        if (fill == null && stroke == null) return this
        return copy(
            fillArgb = fill?.argb ?: fillArgb,
            strokeArgb = stroke?.argb ?: strokeArgb,
        )
    }

    private fun TextLayerParams.resolvedWith(texts: Map<String, TextStyle>): TextLayerParams {
        val style = styleId?.let { texts[it] } ?: return this
        // Text content is never part of a style — only the typography is.
        return copy(
            fontName = style.fontName,
            fontSizeDp = style.fontSizeDp,
            colorArgb = style.colorArgb,
            letterSpacingEm = style.letterSpacingEm,
            isBold = style.isBold,
            isItalic = style.isItalic,
        )
    }
}
