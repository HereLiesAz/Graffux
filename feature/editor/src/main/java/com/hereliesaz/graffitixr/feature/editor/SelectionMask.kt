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
     * Each ring is mapped through the same [ImageProcessor.mapScreenToBitmap] the stroke points use,
     * so it lands where the paint lands regardless of the layer's scale/offset/rotation, and the
     * rings are then combined in order with [Path.op] — union for an additive ring, difference for a
     * subtractive one. That is the whole implementation of Add and Remove: `Path.op` is a real
     * boolean operation on the outlines, so overlapping added regions merge into one silhouette
     * rather than leaving their shared edges behind.
     *
     * An inverted selection is the full-bitmap rect minus that region, expressed as one more
     * difference rather than a deprecated `Region.Op.DIFFERENCE` clip.
     */
    fun bitmapPath(
        selection: Selection?,
        bitmapWidth: Int,
        bitmapHeight: Int,
        layerScale: Float = 1f,
        layerOffset: Offset = Offset.Zero,
        layerRotationZ: Float = 0f,
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
            )
            if (mapped.size < 3) continue
            val ringPath = Path()
            // Even-odd, to match SelectionGeometry.insidePolygon. Path defaults to non-zero
            // winding, and the two disagree on a ring that crosses itself — a lasso whose tail
            // loops back over its own path encloses a doubly-wound patch, which winding fills and
            // even-odd does not. That is the one case where the paint and the hit-test could give
            // different answers about the same pixel: tapping the patch would start a new lasso
            // instead of moving the region it is visibly part of.
            ringPath.fillType = Path.FillType.EVEN_ODD
            ringPath.moveTo(mapped[0].x, mapped[0].y)
            for (i in 1 until mapped.size) ringPath.lineTo(mapped[i].x, mapped[i].y)
            ringPath.close()
            if (!any) {
                // Nothing to combine with yet. A subtractive ring first cuts out of an empty
                // region, which is still empty — skip it rather than seeding the region with it.
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
     *
     * The feather is authored in screen px — it is a property of the selection the user drew, not of
     * whichever layer it lands on — so it scales with the same factor the brush size does. Without
     * that, the same selection would feather visibly harder on a high-resolution layer than on a
     * low-resolution one sitting right beside it.
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
     *
     * Null when feathering — and that is the whole trick. A canvas clip is binary, so a feathered
     * edge cannot be expressed as one at all; instead the paint goes down *unclipped* and [feather]
     * masks it afterwards. Clipping first would confine the paint to the hard boundary, leaving the
     * soft ramp with nothing to reveal on the outer side and turning a symmetric feather into one
     * that only ever fades inwards.
     */
    fun paintClip(clipPath: Path?, featherRadius: Float): Path? =
        if (featherRadius > 0f) null else clipPath

    /**
     * Builds the soft-edged alpha mask for [clipPath] — opaque well inside the region, transparent
     * well outside, ramped across [radiusPx]. Null when there is nothing to feather.
     */
    fun featherMask(clipPath: Path?, bitmapWidth: Int, bitmapHeight: Int, radiusPx: Float): android.graphics.Bitmap? {
        if (clipPath == null || radiusPx <= 0f) return null
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null
        val mask = SafeBitmap.create(bitmapWidth, bitmapHeight) ?: return null
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.FILL
            maskFilter = android.graphics.BlurMaskFilter(radiusPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(mask).drawPath(clipPath, paint)
        return mask
    }

    /**
     * Composites an unclipped [painted] back over [base] through a feathered mask of [clipPath],
     * which is what actually confines the paint when the selection has a soft edge.
     *
     * Returns [painted] untouched when there is no feathering, so every caller can route through
     * this unconditionally and the hard-edged path stays exactly as cheap as it was.
     *
     * **Takes ownership of [painted].** When a distinct bitmap is returned, [painted] has been
     * superseded and is recycled here — it is an intermediate nobody can still be looking at, and
     * every call site is `return feather(...)` on a buffer it allocated a few lines earlier. Left to
     * the callers it was simply dropped: each feathered stroke, fill, clear and flood leaked a
     * full-resolution layer copy for the collector to find, on a heap that already holds two bitmaps
     * per layer. [base] is never recycled — the caller owns that — and neither is [painted] when it
     * *is* [base], which is what an empty stroke or a failed allocation upstream hands us.
     */
    fun feather(
        base: android.graphics.Bitmap,
        painted: android.graphics.Bitmap,
        clipPath: Path?,
        radiusPx: Float,
    ): android.graphics.Bitmap {
        val mask = featherMask(clipPath, painted.width, painted.height, radiusPx) ?: return painted
        val soft = SafeBitmap.copy(painted) ?: run { mask.recycle(); return painted }
        // Keep the paint only where the mask says, at the mask's own alpha — this is where the ramp
        // becomes a partial stroke rather than a present-or-absent one.
        Canvas(soft).drawBitmap(mask, 0f, 0f, android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        })
        val out = SafeBitmap.copy(base) ?: run { mask.recycle(); soft.recycle(); return painted }
        Canvas(out).drawBitmap(soft, 0f, 0f, null)
        mask.recycle()
        soft.recycle()
        if (painted !== base) painted.recycle()
        return out
    }

    /**
     * Confines an **already-produced** bitmap to the selection: [produced] inside the region,
     * [base] outside it.
     *
     * Distinct from [feather], and the distinction matters. [feather] assumes the paint was already
     * clipped while it was being drawn, and does nothing at all at radius 0 — it exists only to add
     * the soft ramp a canvas clip cannot express. That is right for every tool that paints through a
     * `Canvas`, because those clip as they go.
     *
     * It is wrong for anything that produces a whole bitmap and cannot be clipped mid-flight. Liquify
     * is the case: its warp happens in the native pass, which owns the entire bitmap, so by the time
     * there is anything to mask the paint has already landed everywhere. Handing that to [feather]
     * with a hard-edged selection returned it untouched, and the warp escaped the lasso.
     *
     * **Takes ownership of [produced]**, on the same terms as [feather]: superseded, so recycled,
     * unless it is [base] itself or it is what comes back.
     */
    fun confine(
        base: android.graphics.Bitmap,
        produced: android.graphics.Bitmap,
        clipPath: Path?,
        radiusPx: Float,
    ): android.graphics.Bitmap {
        if (clipPath == null) return produced
        if (radiusPx > 0f) return feather(base, produced, clipPath, radiusPx)
        val out = SafeBitmap.copy(base) ?: return produced
        val canvas = Canvas(out)
        canvas.save()
        canvas.clipPath(clipPath)
        // SRC, not SRC_OVER: the produced bitmap replaces what is under it inside the region rather
        // than blending with it, which is what "these pixels are now those pixels" means. Blending
        // would show the old layer through anywhere the warp left transparency.
        canvas.drawBitmap(produced, 0f, 0f, android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
        })
        canvas.restore()
        if (produced !== base) produced.recycle()
        return out
    }

    /**
     * The selected pixels of [source] on their own, transparent everywhere else — what Copy takes.
     *
     * Full-canvas sized rather than cropped to the selection's bounds. That costs a full bitmap, but
     * it makes paste-in-place free: the result drops onto a new layer at the origin and lands in
     * exact register with where it was cut from, with no offset to carry, re-apply, or get wrong
     * when the layer it came from is a different size to the one it lands on.
     *
     * Honours the feather, so copying a soft-edged selection takes soft-edged pixels.
     */
    fun lift(source: android.graphics.Bitmap, clipPath: Path?, featherRadius: Float): android.graphics.Bitmap? {
        if (clipPath == null) return SafeBitmap.copy(source)
        val mask = featherMask(clipPath, source.width, source.height, featherRadius)
        if (mask != null) {
            val out = SafeBitmap.copy(source) ?: run { mask.recycle(); return null }
            Canvas(out).drawBitmap(mask, 0f, 0f, android.graphics.Paint().apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
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
    fun moveRegion(
        source: android.graphics.Bitmap,
        clipPath: Path,
        dx: Float,
        dy: Float,
        /** Feather radius in bitmap px; 0 for the hard-edged move. */
        featherRadius: Float = 0f,
    ): android.graphics.Bitmap {
        if (featherRadius > 0f) {
            val mask = featherMask(clipPath, source.width, source.height, featherRadius)
            if (mask != null) return moveRegionSoft(source, mask, dx, dy)
        }
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
     * [moveRegion] for a feathered selection: same lift-clear-stamp, but every step attenuated by
     * the mask's alpha instead of switched by a clip.
     *
     * The lift takes the mask's alpha (DST_IN) rather than a hard clip, and the hole is cleared *in
     * proportion* to it (DST_OUT) rather than wiped. That pairing is what makes the edge conserve
     * ink: a pixel the mask says is half in leaves half behind and carries half away, so a feathered
     * move doesn't leave a bright rim where the clear undershot the lift.
     */
    private fun moveRegionSoft(
        source: android.graphics.Bitmap,
        mask: android.graphics.Bitmap,
        dx: Float,
        dy: Float,
    ): android.graphics.Bitmap {
        val out = SafeBitmap.copy(source) ?: run { mask.recycle(); return source }
        val lifted = SafeBitmap.copy(source) ?: run { mask.recycle(); return out }
        Canvas(lifted).drawBitmap(mask, 0f, 0f, android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        })
        val outCanvas = Canvas(out)
        outCanvas.drawBitmap(mask, 0f, 0f, android.graphics.Paint().apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        })
        outCanvas.drawBitmap(lifted, dx, dy, null)
        lifted.recycle()
        mask.recycle()
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
    ): Offset {
        val pts = ImageProcessor.mapScreenToBitmap(
            listOf(Offset.Zero, delta), canvasWidth, canvasHeight, bitmapWidth, bitmapHeight,
            layerScale, layerOffset, layerRotationZ,
        )
        return if (pts.size == 2) pts[1] - pts[0] else Offset.Zero
    }
}
