package com.hereliesaz.graffitixr.feature.editor.threed

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
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
            stamps.forEach { canvas.drawCircle(it.x, it.y, radius, paint) }
            isPainted = true
        }
    }

    companion object {
        /** A stroke's surface: a mutable copy of [current] (the layer's paint), or blank. */
        fun startingFrom(current: Bitmap?, width: Int, height: Int): PaintableTexture {
            val copy = current?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, true)
            return PaintableTexture(copy ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
        }
    }
}
