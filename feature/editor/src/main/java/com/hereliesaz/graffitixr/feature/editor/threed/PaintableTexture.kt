// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/threed/PaintableTexture.kt
package com.hereliesaz.graffitixr.feature.editor.threed

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import androidx.core.graphics.createBitmap
import com.hereliesaz.graffitixr.common.mesh.TexturePaint

/**
 * The paint layer of a 3D model: a texture the user draws into, sampled over the lit surface.
 *
 * Painting a model means painting its texture. The dab goes in at the UV the touch resolved to, so
 * it lives on the surface itself — rotate the model afterwards and the paint rotates with it,
 * because it was never in screen space to begin with.
 *
 * Starts fully transparent, so an unpainted model renders as its own shaded self and paint reads as
 * something applied to it rather than a skin replacing it.
 */
class PaintableTexture(val width: Int, val height: Int) {

    val bitmap: Bitmap = createBitmap(width, height)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Bumped on every change. The renderer compares it against what it last uploaded, so a frame
     * that changed nothing costs no texture upload — and one that did is never missed.
     */
    @Volatile
    var version: Int = 0
        private set

    /**
     * Paints [stamps] (texture pixels, from [TexturePaint.stamps]) in [color].
     *
     * [hardness] runs 0..1: 1 is a hard round dab, lower values feather the edge. Feathering costs
     * a mask filter per dab, so it's only built when actually asked for.
     */
    fun stamp(stamps: List<TexturePaint.Stamp>, color: Int, radiusPx: Float, hardness: Float = 0.8f) {
        if (stamps.isEmpty()) return
        val radius = radiusPx.coerceAtLeast(0.5f)
        synchronized(this) {
            paint.reset()
            paint.isAntiAlias = true
            paint.color = color
            paint.style = Paint.Style.FILL
            val feather = (1f - hardness.coerceIn(0f, 1f)) * radius
            paint.maskFilter = if (feather > 0.5f) {
                BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
            } else {
                null
            }
            stamps.forEach { canvas.drawCircle(it.x, it.y, radius, paint) }
            version++
        }
    }

    /**
     * Replaces the paint with [saved] — the texture read back off disk when a project reopens.
     *
     * Drawn rather than swapped in so the bitmap object stays the one the renderer already holds,
     * and scaled to fit in case the file was written when [DEFAULT_SIZE] was something else.
     * [version] moves, so the renderer re-uploads without needing to know a restore happened.
     */
    fun restore(saved: Bitmap) {
        synchronized(this) {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            val dest = android.graphics.Rect(0, 0, width, height)
            canvas.drawBitmap(saved, null, dest, Paint(Paint.FILTER_BITMAP_FLAG))
            version++
        }
    }

    /** Wipes the paint back to bare model. */
    fun clear() {
        synchronized(this) {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            version++
        }
    }

    /** True once anything has been painted — used to decide whether there's work worth keeping. */
    val isPainted: Boolean get() = version > 0

    /**
     * Runs [block] against the bitmap with painting held off.
     *
     * The GL thread reads these pixels while the UI thread draws into them; without this a dab
     * landing mid-upload can tear across the texture. Held only for the upload itself, which is a
     * single memory copy.
     */
    fun <T> withPixels(block: (Bitmap) -> T): T = synchronized(this) { block(bitmap) }

    companion object {
        /**
         * The texture a newly loaded model gets. Square and modest: this is a paint surface on a
         * phone, and every doubling costs four times the memory for detail nobody can see at the
         * size a model is inspected at here.
         */
        const val DEFAULT_SIZE = 1024
    }
}
