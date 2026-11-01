@file:Suppress("ReturnCount", "MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.VectorShape
import kotlin.math.abs
import kotlin.math.ceil

/**
 * A vector layer's shapes as pixels, for the places that need an image of them: the GPU depth
 * compositor and the glTF scene export (spec/3d-space.md). Drawn by the very [drawVectorShape] the
 * canvas uses, so fills, strokes, corner radii and curves come out exactly as on screen; that is why
 * the export rasterizes rather than tessellates (a tessellator would have to re-implement stroking,
 * joins and curves, and would still disagree with the canvas at the edges).
 */
internal object VectorRaster {
    /** The image and where it sits in the layer's full-canvas box: left, top, right, bottom. */
    class Image(val bitmap: Bitmap, val rect: FloatArray)

    /** Half the side of the square around the box centre that holds every shape. */
    fun halfExtent(layer: Layer): Float = layer.shapes.maxOfOrNull { s ->
        maxOf(abs(s.width) / 2f, abs(s.height) / 2f, controlExtent(s)) + s.strokeWidth
    } ?: 0f

    /**
     * The largest |coordinate| of a path's nodes and their control points. Handles are offsets
     * relative to their node, so a control point is node + handle; a cubic stays inside the hull
     * of its control points, so this bounds every curve.
     */
    private fun controlExtent(s: VectorShape): Float {
        var m = 0f
        for (i in s.points.indices) {
            val p = s.points[i]
            m = maxOf(m, abs(p), abs(p + s.handlesIn.getOrElse(i) { 0f }), abs(p + s.handlesOut.getOrElse(i) { 0f }))
        }
        return m
    }

    /** Null for a layer without shapes or one too large to hold ([maxSide] pixels a side). */
    fun rasterize(layer: Layer, boxWidth: Float, boxHeight: Float, maxSide: Int = MAX_SIDE): Image? {
        if (layer.shapes.isEmpty()) return null
        val half = halfExtent(layer)
        if (!half.isFinite() || half <= 0f) return null
        val side = ceil(half * 2f).toInt().coerceAtLeast(1)
        val scale = if (side > maxSide) maxSide.toFloat() / side else 1f
        val px = (side * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), Size(px.toFloat(), px.toFloat())) {
            drawContext.transform.scale(scale, scale, androidx.compose.ui.geometry.Offset.Zero)
            layer.shapes.forEach { drawVectorShape(it, half, half) }
        }
        val cx = boxWidth / 2f
        val cy = boxHeight / 2f
        return Image(bitmap, floatArrayOf(cx - half, cy - half, cx + half, cy + half))
    }

    private const val MAX_SIDE = 4096
}

/**
 * Rasters kept per layer between scene rebuilds. Keyed on the shapes and the canvas size together:
 * [VectorRaster.Image.rect] is centred on the box it was rasterized for, so a resized canvas must not
 * reuse the old placement.
 */
internal class VectorRasterCache(
    private val raster: (Layer, Float, Float) -> VectorRaster.Image? = { l, w, h -> VectorRaster.rasterize(l, w, h) },
) {
    private class Entry(val shapes: List<VectorShape>, val width: Float, val height: Float, val image: VectorRaster.Image?) {
        fun matches(s: List<VectorShape>, w: Float, h: Float) = shapes == s && width == w && height == h
    }
    private val entries = HashMap<String, Entry>()

    fun get(layer: Layer, width: Float, height: Float): VectorRaster.Image? {
        val e = entries[layer.id]
        if (e != null && e.matches(layer.shapes, width, height)) return e.image
        return raster(layer, width, height).also { entries[layer.id] = Entry(layer.shapes, width, height, it) }
    }
}
