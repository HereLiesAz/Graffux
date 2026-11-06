package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.frame
import com.hereliesaz.graffitixr.common.scene.PaintTarget
import com.hereliesaz.graffitixr.common.scene.SurfaceHull
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import com.hereliesaz.graffitixr.common.scene.SurfaceView
import com.hereliesaz.graffitixr.common.scene.SurfaceViewFlood
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import kotlin.math.abs

/**
 * The pixels behind [SurfaceViewFlood]: how a flood fill spreads in each view image of a paint layer
 * (spec/3d-space.md, "Fill, Clone, lasso"). A view with no image yet is blank, so its whole face floods.
 * [mask] is the flood (the magic wand's, same tolerance as the bucket), [clip] the lasso cut to a view
 * (null selection: none; blocked: the view is out of reach).
 */
internal class ViewFloodOracle(
    private val layers: List<Layer>,
    private val primaryId: String,
    private val hull: SurfaceHull,
    private val canvasW: Int,
    private val canvasH: Int,
    private val tapColor: Int,
    private val mask: (Bitmap, Int, Int, Int) -> BooleanArray?,
    private val clip: (SurfaceView) -> EditorViewModel.ViewClip,
) : SurfaceViewFlood.Oracle {
    private fun image(view: SurfaceView): Layer? =
        layers.firstOrNull { it.surfaceViewOf == primaryId && it.surfaceView == view && it.bitmap != null }

    private fun flatOf(image: Layer, x: Float, y: Float): Offset? {
        val owner = SurfaceMath.ownerOf(layers, image.id) ?: return null
        return SurfaceMath.unrolledToFlat(x, y, owner.innerChain, canvasW.toFloat(), canvasH.toFloat())?.let { Offset(it.first, it.second) }
    }

    /** The bitmap pixel of paint point ([x], [y]) in [image]. */
    private fun pixelOf(image: Layer, bmp: Bitmap, x: Float, y: Float): Pair<Int, Int>? {
        val flat = flatOf(image, x, y) ?: return null
        val p = ImageProcessor.mapScreenToBitmap(
            listOf(flat), canvasW, canvasH, image.frame(bmp.width, bmp.height), image.scale, image.offset, image.rotationZ,
        ).firstOrNull() ?: return null
        return Math.floor(p.x.toDouble()).toInt() to Math.floor(p.y.toDouble()).toInt()
    }

    private fun paintOf(image: Layer, bmp: Bitmap, px: Int, py: Int): Pair<Float, Float>? {
        val owner = SurfaceMath.ownerOf(layers, image.id) ?: return null
        val flat = ImageProcessor.mapBitmapToScreen(
            listOf(Offset(px + 0.5f, py + 0.5f)), canvasW, canvasH, image.frame(bmp.width, bmp.height),
            image.scale, image.offset, image.rotationZ,
        ).firstOrNull() ?: return null
        return SurfaceMath.flatToUnrolled(flat.x, flat.y, owner.innerChain, canvasW.toFloat(), canvasH.toFloat())
    }

    private fun inFace(t: PaintTarget, x: Float, y: Float): Boolean {
        val r = hull.regionRect(t.view, t.region) ?: return false
        return x >= r[0] - SLACK && x <= r[2] + SLACK && y >= r[1] - SLACK && y <= r[3] + SLACK
    }

    private fun matches(c: Int): Boolean =
        abs((c ushr 24) - (tapColor ushr 24)) <= TOLERANCE &&
            abs(((c ushr 16) and 0xFF) - ((tapColor ushr 16) and 0xFF)) <= TOLERANCE &&
            abs(((c ushr 8) and 0xFF) - ((tapColor ushr 8) and 0xFF)) <= TOLERANCE &&
            abs((c and 0xFF) - (tapColor and 0xFF)) <= TOLERANCE

    override fun flood(seed: PaintTarget): ((Float, Float) -> Boolean)? {
        val c = clip(seed.view)
        if (c.blocked) return null
        val img = image(seed.view)
        val bmp = img?.bitmap
        val selection: Selection? = c.selection
        fun allowed(x: Float, y: Float): Boolean {
            if (!inFace(seed, x, y)) return false
            val sel = selection ?: return true
            val flat = img?.let { flatOf(it, x, y) } ?: Offset(x, y)
            return SelectionGeometry.contains(sel, flat)
        }
        if (img == null || bmp == null) return { x, y -> allowed(x, y) }
        val (sx, sy) = pixelOf(img, bmp, seed.x, seed.y) ?: return null
        val m = mask(bmp, sx, sy, TOLERANCE) ?: return null
        return { x, y ->
            val (px, py) = pixelOf(img, bmp, x, y) ?: (-1 to -1)
            allowed(x, y) && (-1..1).any { dy ->
                (-1..1).any { dx ->
                    val qx = px + dx
                    val qy = py + dy
                    qx in 0 until bmp.width && qy in 0 until bmp.height && m[qy * bmp.width + qx]
                }
            }
        }
    }

    override fun accept(target: PaintTarget): PaintTarget? {
        val img = image(target.view)
        val bmp = img?.bitmap
        if (img == null || bmp == null) return target.takeIf { matches(0) && !clip(target.view).blocked }
        val (px, py) = pixelOf(img, bmp, target.x, target.y) ?: return null
        for (dy in -1..1) for (dx in -1..1) {
            val qx = px + dx
            val qy = py + dy
            if (qx !in 0 until bmp.width || qy !in 0 until bmp.height || !matches(bmp.getPixel(qx, qy))) continue
            val (x, y) = paintOf(img, bmp, qx, qy) ?: continue
            if (inFace(target, x, y)) return PaintTarget(target.view, x, y, target.region)
        }
        return null
    }

    /** The flat point of [t] in its view image (a blank view's own flat space is the unrolled one). */
    fun flatOfTarget(t: PaintTarget, imageLayers: List<Layer>): Offset? {
        val img = imageLayers.firstOrNull { it.surfaceViewOf == primaryId && it.surfaceView == t.view } ?: return null
        return flatOf(img, t.x, t.y)
    }

    private companion object {
        const val TOLERANCE = 32
        const val SLACK = 1.5f
    }
}
