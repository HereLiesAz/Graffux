package com.hereliesaz.graffitixr.feature.editor

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Region
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor

/**
 * Turns a screen-space [Selection] into the bitmap-space clip every raster paint site applies.
 *
 * There is exactly one place that decides what "inside the selection" means in pixels, and this is
 * it — the live preview, the commit, and the history replay all clip through [clip], so a stroke
 * painted inside a selection re-composites identically after an undo.
 */
internal object SelectionMask {

    /**
     * The selected region as a bitmap-space [Path], or null when there is nothing to clip to.
     *
     * Each ring is mapped through the same [ImageProcessor.mapScreenToBitmap] the stroke points use,
     * so it lands where the paint lands regardless of the layer's scale/offset/rotation and camera viewport.
     */
    fun bitmapPath(
        selection: Selection?,
        bitmapWidth: Int,
        bitmapHeight: Int,
        layerScale: Float = 1f,
        layerOffset: Offset = Offset.Zero,
        layerRotationZ: Float = 0f,
        viewportOffset: Offset = Offset.Zero,
        viewportZoom: Float = 1f,
        viewportRotation: Float = 0f,
    ): Path? {
        if (selection == null || !selection.isUsable) return null
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null

        val region = Path()
        var any = false
        for (ring in selection.rings) {
            if (!ring.isUsable) continue
            val mapped = ImageProcessor.mapScreenToBitmap(
                ring.path, selection.canvasSize.width, selection.canvasSize.height,
                bitmapWidth, bitmapHeight, layerScale, layerOffset, layerRotationZ,
                Offset.Zero, 1f, 0f,
            )
            if (mapped.size < 3) continue
            val ringPath = Path()
            ringPath.fillType = Path.FillType.EVEN_ODD
            ringPath.moveTo(mapped[0].x, mapped[0].y)
            for (i in 1 until mapped.size) ringPath.lineTo(mapped[i].x, mapped[i].y)
            ringPath.close()
            if (!any) {
                if (!ring.additive) continue
                region.set(ringPath)
                any = true
            } else {
                region.op(ringPath, if (ring.additive) Path.Op.UNION else Path.Op.DIFFERENCE)
            }
        }
        if (!any) return null

        if (selection.inverted) {
            val whole = Path().apply {
                addRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), Path.Direction.CW)
            }
            whole.op(region, Path.Op.DIFFERENCE)
            return whole
        }
        return region
    }

    /**
     * [Selection.featherPx] converted into this bitmap's pixels, or 0 when the edge is hard.
     */
    fun featherRadius(
        selection: Selection?,
        bitmapWidth: Int,
        bitmapHeight: Int,
        layerScale: Float = 1f,
    ): Float {
        val px = selection?.featherPx ?: 0f
        if (px <= 0f || !(selection?.isUsable ?: false)) return 0f
        val scale = ImageProcessor.screenToBitmapScale(
            selection.canvasSize.width, selection.canvasSize.height, bitmapWidth, bitmapHeight, layerScale,
        )
        return (px * scale).coerceAtLeast(0f)
    }

    /**
     * The clip to hand the paint, given the hard boundary and a feather radius.
     */
    fun paintClip(clipPath: Path?, featherRadius: Float): Path? =
        if (featherRadius > 0f) null else clipPath

    /**
     * Composites [painted] back over [base] through the feathered selection mask.
     */
    fun feather(
        base: android.graphics.Bitmap,
        painted: android.graphics.Bitmap,
        clipPath: Path?,
        featherRadius: Float,
    ): android.graphics.Bitmap {
        if (clipPath == null || featherRadius <= 0f) return painted
        val mask = featherMask(clipPath, base.width, base.height, featherRadius) ?: return painted
        val out = SafeBitmap.copy(base) ?: run { mask.recycle(); return painted }
        val canvas = Canvas(out)
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        val temp = SafeBitmap.copy(painted) ?: run { mask.recycle(); return painted }
        Canvas(temp).drawBitmap(mask, 0f, 0f, paint)
        canvas.drawBitmap(temp, 0f, 0f, null)
        temp.recycle()
        mask.recycle()
        return out
    }

    /**
     * Composites [warped] over [base] inside [clipPath] / [featherRadius].
     */
    fun confine(
        base: android.graphics.Bitmap,
        warped: android.graphics.Bitmap,
        clipPath: Path?,
        featherRadius: Float = 0f,
    ): android.graphics.Bitmap {
        if (clipPath == null) return warped
        if (featherRadius > 0f) return feather(base, warped, clipPath, featherRadius)
        val out = SafeBitmap.copy(base) ?: return warped
        val canvas = Canvas(out)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(warped, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        })
        canvas.restore()
        return out
    }

    /**
     * Lift selected pixels into a standalone bitmap.
     */
    fun lift(source: android.graphics.Bitmap, clipPath: Path?, featherRadius: Float): android.graphics.Bitmap? {
        if (clipPath == null) return SafeBitmap.copy(source)
        val mask = featherMask(clipPath, source.width, source.height, featherRadius)
        if (mask != null) {
            val out = SafeBitmap.copy(source) ?: run { mask.recycle(); return null }
            Canvas(out).drawBitmap(mask, 0f, 0f, Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
            mask.recycle()
            return out
        }
        val out = SafeBitmap.create(source.width, source.height) ?: return null
        val canvas = Canvas(out)
        canvas.clipPath(clipPath)
        canvas.drawBitmap(source, 0f, 0f, null)
        return out
    }

    /**
     * Confines everything subsequently drawn on [canvas] to [clipPath].
     */
    fun clip(canvas: Canvas, clipPath: Path?) {
        if (clipPath != null) canvas.clipPath(clipPath)
    }

    /**
     * A [Region] covering [clipPath], for per-pixel work.
     */
    fun region(clipPath: Path?, bitmapWidth: Int, bitmapHeight: Int): Region? {
        if (clipPath == null) return null
        return Region().apply { setPath(clipPath, Region(0, 0, bitmapWidth, bitmapHeight)) }
    }

    /**
     * Moves the selected pixels of [source] by ([dx], [dy]) bitmap px.
     */
    fun moveRegion(
        source: android.graphics.Bitmap,
        clipPath: Path,
        dx: Float,
        dy: Float,
        featherRadius: Float = 0f,
    ): android.graphics.Bitmap {
        if (featherRadius > 0f) {
            val mask = featherMask(clipPath, source.width, source.height, featherRadius)
            if (mask != null) return moveRegionSoft(source, mask, dx, dy)
        }
        val bounds = android.graphics.RectF()
        clipPath.computeBounds(bounds, true)
        val left = bounds.left.toInt().coerceIn(0, source.width)
        val top = bounds.top.toInt().coerceIn(0, source.height)
        val right = kotlin.math.ceil(bounds.right).toInt().coerceIn(left, source.width)
        val bottom = kotlin.math.ceil(bounds.bottom).toInt().coerceIn(top, source.height)
        val w = right - left
        val h = bottom - top
        val out = SafeBitmap.copy(source) ?: return source
        if (w <= 0 || h <= 0) return out

        val lifted = SafeBitmap.create(w, h) ?: return out
        val liftCanvas = Canvas(lifted)
        liftCanvas.translate(-left.toFloat(), -top.toFloat())
        liftCanvas.clipPath(clipPath)
        liftCanvas.drawBitmap(source, 0f, 0f, null)

        val outCanvas = Canvas(out)
        outCanvas.save()
        outCanvas.clipPath(clipPath)
        outCanvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        outCanvas.restore()
        outCanvas.drawBitmap(lifted, left + dx, top + dy, null)
        lifted.recycle()
        return out
    }

    private fun moveRegionSoft(
        source: android.graphics.Bitmap,
        mask: android.graphics.Bitmap,
        dx: Float,
        dy: Float,
    ): android.graphics.Bitmap {
        val out = SafeBitmap.copy(source) ?: run { mask.recycle(); return source }
        val lifted = SafeBitmap.copy(source) ?: run { mask.recycle(); return out }
        Canvas(lifted).drawBitmap(mask, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        val outCanvas = Canvas(out)
        outCanvas.drawBitmap(mask, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        })
        outCanvas.drawBitmap(lifted, dx, dy, null)
        lifted.recycle()
        mask.recycle()
        return out
    }

    /**
     * Map delta carrying viewport parameters.
     */
    fun mapDelta(
        delta: Offset,
        canvasWidth: Int,
        canvasHeight: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        layerScale: Float,
        layerOffset: Offset,
        layerRotationZ: Float,
        viewportOffset: Offset = Offset.Zero,
        viewportZoom: Float = 1f,
        viewportRotation: Float = 0f,
    ): Offset {
        val pts = ImageProcessor.mapScreenToBitmap(
            listOf(Offset.Zero, delta), canvasWidth, canvasHeight, bitmapWidth, bitmapHeight,
            layerScale, layerOffset, layerRotationZ,
            viewportOffset, viewportZoom, viewportRotation
        )
        return if (pts.size == 2) pts[1] - pts[0] else Offset.Zero
    }

    fun featherMask(clipPath: Path, width: Int, height: Int, radius: Float): android.graphics.Bitmap? {
        if (radius <= 0f) return null
        val mask = SafeBitmap.create(width, height) ?: return null
        val canvas = Canvas(mask)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(clipPath, paint)
        return mask
    }
}
