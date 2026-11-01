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
        val coords = (s.points + s.handlesIn + s.handlesOut).maxOfOrNull { abs(it) } ?: 0f
        maxOf(abs(s.width) / 2f, abs(s.height) / 2f, coords) + s.strokeWidth
    } ?: 0f

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
