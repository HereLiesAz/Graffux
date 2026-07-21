// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/importer/ImportedDocument.kt
package com.hereliesaz.graffitixr.common.importer

import com.hereliesaz.graffitixr.common.model.BlendMode

/**
 * A single decoded layer from an imported design document (PSD, …), in a device-independent form:
 * plain ARGB pixels plus the placement/compositing metadata needed to reproduce it as an editor
 * [com.hereliesaz.graffitixr.common.model.Layer]. Deliberately free of Android types so the parsers
 * and their tests run on a plain JVM; the Android boundary turns [argb] into a `Bitmap`.
 *
 * [argb] is row-major, `width * height` entries, each packed `0xAARRGGBB`. [left]/[top] are the
 * layer's top-left within the document canvas (document pixels); a layer is usually smaller than the
 * canvas and offset into it.
 *
 * Not a `data class`: an [IntArray] has no structural `equals`, so the generated one would be
 * misleading. Equality is not needed here — consumers read the fields.
 */
class ImportedLayer(
    val name: String,
    val argb: IntArray,
    val width: Int,
    val height: Int,
    val left: Int = 0,
    val top: Int = 0,
    val opacity: Float = 1f,
    val isVisible: Boolean = true,
    val blendMode: BlendMode = BlendMode.SrcOver,
    /** Editable vector outlines (from a PSD vector mask / shape layer), in document pixels. Empty for
     *  a plain raster layer. Lets the importer preserve real vectors instead of only their pixels. */
    val vectorPaths: List<ImportedPath> = emptyList(),
) {
    /** The pixel at ([x], [y]) as packed ARGB, or `0` (transparent) if out of bounds. */
    fun pixel(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) 0 else argb[y * width + x]
}

/**
 * An editable vector outline extracted from a source document (a PSD vector mask / shape layer).
 * [points] are interleaved `[x0,y0,x1,y1,…]` in **document pixels** (top-left origin); [closed] joins
 * the last point back to the first. A poly-line approximation of the original Béziers (anchor points
 * only) — enough to reproduce the shape as an editable path.
 */
class ImportedPath(
    val points: List<Float>,
    val closed: Boolean,
)

/**
 * A parsed multi-layer document ready to be mapped onto editor layers. [width]/[height] are the
 * document canvas size in pixels; [layers] are ordered bottom-to-top (index 0 is the backmost),
 * matching the editor's own layer-stack order.
 */
class ImportedDocument(
    val width: Int,
    val height: Int,
    val layers: List<ImportedLayer>,
)
