package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Canvas
import android.graphics.Path
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
     * The polygon is mapped through the same [ImageProcessor.mapScreenToBitmap] the stroke points
     * use, so it lands where the paint lands regardless of the layer's scale/offset/rotation.
     * An inverted selection is expressed as the full-bitmap rect combined with the lasso under
     * [Path.FillType.EVEN_ODD] — the rect minus the lasso — rather than a deprecated
     * `Region.Op.DIFFERENCE` clip.
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
        val mapped = ImageProcessor.mapScreenToBitmap(
            selection.path, selection.canvasSize.width, selection.canvasSize.height,
            bitmapWidth, bitmapHeight, layerScale, layerOffset, layerRotationZ,
            Offset.Zero, 1f, 0f // Selection is already in world space
        )
        if (mapped.size < 3) return null
        val path = Path()
        path.moveTo(mapped[0].x, mapped[0].y)
        for (i in 1 until mapped.size) path.lineTo(mapped[i].x, mapped[i].y)
        path.close()
        if (selection.inverted) {
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), Path.Direction.CW)
        }
        return path
    }

    /**
     * Confines everything subsequently drawn on [canvas] to [clipPath]. A no-op for a null path,
     * so callers can apply it unconditionally.
     *
     * Callers own the save/restore: the canvases here are either freshly created for one operation
     * or already bracketed by the caller, and clipping is not undoable in place.
     */
    fun clip(canvas: Canvas, clipPath: Path?) {
        if (clipPath != null) canvas.clipPath(clipPath)
    }

    /**
     * A [Region] covering [clipPath], for per-pixel work that can't go through a Canvas clip —
     * the scanline flood fill. Null when unclipped (fill the whole bitmap).
     */
    fun region(clipPath: Path?, bitmapWidth: Int, bitmapHeight: Int): Region? {
        if (clipPath == null) return null
        return Region().apply { setPath(clipPath, Region(0, 0, bitmapWidth, bitmapHeight)) }
    }

    /**
     * Moves the selected pixels of [source] by ([dx], [dy]) bitmap px: the region is lifted, the
     * hole it leaves is cleared to transparent, and the lift is stamped back at its new position.
     * Returns a new bitmap; [source] is left untouched.
     *
     * Deterministic from its inputs, which is what lets a move be recorded as an ordinary
     * [StrokeCommand] and replayed by [DrawingEngine] on undo/redo like any stroke.
     */
    fun moveRegion(source: android.graphics.Bitmap, clipPath: Path, dx: Float, dy: Float): android.graphics.Bitmap {
        // Only the selection's bounds are lifted, not a second full-canvas buffer. A full-size lift
        // cost another width*height*4 bytes on top of the output copy, which on a phone-resolution
        // layer is tens of megabytes for what is usually a small region — enough, stacked on the
        // layers already resident, to be the allocation that fails.
        val bounds = android.graphics.RectF()
        clipPath.computeBounds(bounds, true)
        val left = bounds.left.toInt().coerceIn(0, source.width)
        val top = bounds.top.toInt().coerceIn(0, source.height)
        val right = kotlin.math.ceil(bounds.right).toInt().coerceIn(left, source.width)
        val bottom = kotlin.math.ceil(bounds.bottom).toInt().coerceIn(top, source.height)
        val w = right - left
        val h = bottom - top
        val out = SafeBitmap.copy(source) ?: return source
        // An empty region has nothing to move; the copy is still the right answer.
        if (w <= 0 || h <= 0) return out

        // Without the lift there is nothing to move; returning the untouched copy is the
        // honest degradation — better a move that didn't happen than a crash mid-drag.
        val lifted = SafeBitmap.create(w, h) ?: return out
        val liftCanvas = Canvas(lifted)
        // Shift the clip into the lift's own coordinate space before using it.
        liftCanvas.translate(-left.toFloat(), -top.toFloat())
        liftCanvas.clipPath(clipPath)
        liftCanvas.drawBitmap(source, 0f, 0f, null)

        val outCanvas = Canvas(out)
        // Clear the hole, then stamp the lift back down offset by the drag.
        outCanvas.save()
        outCanvas.clipPath(clipPath)
        outCanvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        outCanvas.restore()
        outCanvas.drawBitmap(lifted, left + dx, top + dy, null)
        lifted.recycle()
        return out
    }

    /**
     * The selection's drag translation expressed in bitmap pixels. [delta] is screen-space, so it
     * is carried through the affine screen→bitmap mapping as a *difference* of two mapped points —
     * that applies the scale and the layer rotation while cancelling the translation, which a
     * straight scalar multiply would get wrong on a rotated layer.
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
        viewportOffset: Offset,
        viewportZoom: Float,
        viewportRotation: Float,
    ): Offset {
        val pts = ImageProcessor.mapScreenToBitmap(
            listOf(Offset.Zero, delta), canvasWidth, canvasHeight, bitmapWidth, bitmapHeight,
            layerScale, layerOffset, layerRotationZ,
            viewportOffset, viewportZoom, viewportRotation
        )
        return if (pts.size == 2) pts[1] - pts[0] else Offset.Zero
    }
}
