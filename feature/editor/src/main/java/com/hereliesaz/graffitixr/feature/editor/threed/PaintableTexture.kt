package com.hereliesaz.graffitixr.feature.editor.threed

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.hereliesaz.graffitixr.common.mesh.TexturePaint

/**
 * The paint of a 3D model layer while a stroke is on it: dabs go into [bitmap] at the UV the touch
 * resolved to, so the paint lives on the surface and turns with the model.
 *
 * [bitmap] must be mutable and owned by this stroke: when the stroke ends it becomes the layer's
 * new texture revision and is never painted again. Dabs are drawn under the bitmap's own monitor,
 * which the depth pass also holds while it uploads the pixels, so an upload never tears.
 */
class PaintableTexture(val bitmap: Bitmap) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var clip: Path? = null

    /**
     * Limits the dabs that follow to the UV triangles in [uvTriangles] (`u0,v0,u1,v1,u2,v2` per
     * triangle, in 0..1), so paint on one UV island cannot bleed onto the texels of the island laid
     * out beside it. Null removes the limit. The clip is not anti-aliased, which on a texture is the
     * same hard edge the island's own texels have.
     */
    fun clipTo(uvTriangles: FloatArray?) {
        clip = uvTriangles?.takeIf { it.size >= TRIANGLE_FLOATS }?.let(::trianglePath)
    }

    private fun trianglePath(uv: FloatArray): Path = Path().apply {
        fillType = Path.FillType.WINDING
        for (t in 0 until uv.size / TRIANGLE_FLOATS) {
            val o = t * TRIANGLE_FLOATS
            val xs = FloatArray(CORNERS) { uv[o + it * 2] * width }
            val ys = FloatArray(CORNERS) { uv[o + it * 2 + 1] * height }
            // One winding for every triangle: a mirrored one would otherwise cancel its neighbour
            // where the two overlap under the non-zero fill rule.
            val flipped = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0]) < 0f
            val order = if (flipped) intArrayOf(0, 2, 1) else intArrayOf(0, 1, 2)
            moveTo(xs[order[0]], ys[order[0]])
            lineTo(xs[order[1]], ys[order[1]])
            lineTo(xs[order[2]], ys[order[2]])
            close()
        }
    }

    /** Whether any dab landed. */
    var isPainted: Boolean = false
        private set

    /**
     * Paints [stamps] (texture pixels, from [TexturePaint.stamps]) in [color], or clears them when
     * [erase]. [hardness] runs 0..1: 1 is a hard round dab, lower values feather the edge.
     */
    fun stamp(
        stamps: List<TexturePaint.Stamp>,
        color: Int,
        radiusPx: Float,
        hardness: Float = 0.8f,
        erase: Boolean = false,
    ) {
        if (stamps.isEmpty()) return
        val radius = radiusPx.coerceAtLeast(0.5f)
        synchronized(bitmap) {
            paint.reset()
            paint.isAntiAlias = true
            paint.color = color
            paint.style = Paint.Style.FILL
            paint.xfermode = if (erase) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
            val feather = (1f - hardness.coerceIn(0f, 1f)) * radius
            paint.maskFilter = if (feather > 0.5f) BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL) else null
            val limit = clip
            if (limit != null) canvas.save().also { canvas.clipPath(limit) }
            stamps.forEach { canvas.drawCircle(it.x, it.y, radius, paint) }
            if (limit != null) canvas.restore()
            isPainted = true
        }
    }

    companion object {
        private const val CORNERS = 3
        private const val TRIANGLE_FLOATS = CORNERS * 2

        /** A stroke's surface: a mutable copy of [current] (the layer's paint), or blank. */
        fun startingFrom(current: Bitmap?, width: Int, height: Int): PaintableTexture {
            val copy = current?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, true)
            return PaintableTexture(copy ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
        }
    }
}
