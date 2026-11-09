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
     * Limits the dabs that follow to the UV triangles in [own] (`u0,v0,u1,v1,u2,v2` per triangle, in
     * 0..1) plus a [GUTTER_TEXELS] gutter of padding around them, minus the triangles in
     * [neighbours] (other islands' texels), so paint on one island cannot bleed onto the island laid
     * out beside it but does reach the padding the texture filter samples at its edge. Null [own]
     * removes the limit. The clip is not anti-aliased, the same hard edge the island's own texels have.
     *
     * The gutter matters because the texture is sampled with bilinear filtering: at the island's edge
     * a sample blends the last painted texel with the first one beyond it. If that one is clear the
     * edge shows half coverage (a thin unpainted line along every seam), so the first texels past the
     * edge are painted too, wherever they are padding. They are never painted where another island
     * owns them, and the island's own triangles always stay in even where a neighbour overlaps them.
     */
    fun clipTo(own: FloatArray?, neighbours: FloatArray? = null) {
        clip = own?.takeIf { it.size >= TRIANGLE_FLOATS }?.let { buildClip(it, neighbours) }
    }

    private fun buildClip(own: FloatArray, neighbours: FloatArray?): Path {
        val fill = trianglePath(own)
        val outline = Path()
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = GUTTER_TEXELS * 2f
            strokeJoin = Paint.Join.ROUND
        }.getFillPath(fill, outline)
        // Each boolean op can fail on a pathological path; a failure falls back to the exact island.
        val grown = combine(fill, outline, Path.Op.UNION) ?: fill
        val others = neighbours?.takeIf { it.size >= TRIANGLE_FLOATS }
        val padding = if (others == null) grown else combine(grown, trianglePath(others), Path.Op.DIFFERENCE) ?: fill
        return combine(fill, padding, Path.Op.UNION) ?: fill
    }

    private fun combine(a: Path, b: Path, op: Path.Op): Path? = Path().takeIf { it.op(a, b, op) }

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

        /**
         * How far past an island's edge a dab is allowed into empty padding, in texels. One texel is
         * what a bilinear sample at the edge reaches; the second covers the clip's own half-texel
         * rounding (it is not anti-aliased, so a texel is in or out by its centre). The texture has no
         * mipmaps (see DepthCompositor), so no more is needed; a mipmapped texture would need a
         * gutter of 2^levels.
         */
        const val GUTTER_TEXELS = 2f
        private const val TRIANGLE_FLOATS = CORNERS * 2

        /** A stroke's surface: a mutable copy of [current] (the layer's paint), or blank. */
        fun startingFrom(current: Bitmap?, width: Int, height: Int): PaintableTexture {
            val copy = current?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, true)
            return PaintableTexture(copy ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
        }
    }
}
