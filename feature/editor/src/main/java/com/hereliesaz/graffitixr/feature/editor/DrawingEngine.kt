package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor

/**
 * The stroke-compositing pipeline, extracted from EditorViewModel: turns a base bitmap plus
 * recorded [StrokeCommand]s into a rendered bitmap. This is the CPU/OpenCV-bound "how strokes
 * become pixels" logic; the ViewModel still owns when to invoke it, the dispatcher hop, the
 * UiState update, and persistence.
 *
 * Pure with respect to the editor's state (input bitmap + strokes → output bitmap); its only
 * dependencies are the OpenCV [ImageProcessor] and [SlamManager] (for Liquify warps). Callers
 * run these on a background dispatcher — they are CPU-heavy and must not touch the main thread.
 */
internal class DrawingEngine(private val slamManager: SlamManager) {

    /**
     * Replays [strokes] in order onto a fresh mutable copy of [base], returning the result.
     *
     * Throws if that first copy cannot be allocated. Returning [base] itself would be worse than
     * failing: callers publish the result as the layer's live bitmap, which would alias
     * [LayerStore]'s pristine base into the UI and let the next in-place stroke corrupt the one
     * copy every rebuild and undo depends on. Both callers already treat a thrown rebuild as
     * "leave the layer as it was", which is the correct outcome.
     */
    suspend fun composite(base: Bitmap, strokes: List<StrokeCommand>): Bitmap {
        var current = SafeBitmap.copy(base)
            ?: throw IllegalStateException("Out of memory copying a ${base.width}x${base.height} layer base")
        for (stroke in strokes) {
            val next = if (stroke.tool == Tool.LIQUIFY) applyLiquify(current, stroke)
            else applyTool(current, stroke, replaceExisting = true)
            // Recycle the superseded intermediate so a long stroke history doesn't churn many
            // full-resolution bitmaps into an OOM. Never recycle `base` (the caller owns it) or
            // a tool that mutated `current` in place and returned it.
            if (next !== current && current !== base) current.recycle()
            current = next
        }
        return current
    }

    /**
     * Applies a single freshly-drawn [command] onto [base] without re-replaying history — the
     * commit path for a just-finished stroke. (`replaceExisting = false` matches the original
     * incremental behavior.)
     */
    suspend fun applySingleStroke(base: Bitmap, command: StrokeCommand): Bitmap =
        // Liquify is checked here as well as in [composite], and that asymmetry was a real bug: this
        // function had no LIQUIFY branch, so the commit path could not route a warp through the
        // engine even if it asked to — it fell into applyTool, whose tool switch has no case for
        // LIQUIFY, and returned the layer unchanged. Replay went through applyLiquify. The two
        // agreeing is the whole point of committing through here.
        if (command.tool == Tool.LIQUIFY) applyLiquify(base, command)
        else applyTool(base, command, replaceExisting = false)

    private suspend fun applyTool(bitmap: Bitmap, stroke: StrokeCommand, replaceExisting: Boolean): Bitmap {
        // The lasso in force when this stroke was drawn, in this bitmap's pixel space. Every branch
        // below clips to it, so replaying a stroke reproduces the clipped paint exactly.
        val clipPath = SelectionMask.bitmapPath(
            stroke.selection, bitmap.width, bitmap.height,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
        )
        // A feathered selection cannot be a canvas clip — a clip is binary. The paint goes down
        // unclipped and SelectionMask.feather masks it back against the base afterwards, which is
        // what makes the ramp two-sided. Both are no-ops at radius 0, so the hard-edged path (still
        // the overwhelmingly common one) is untouched and costs nothing.
        val featherRadius = SelectionMask.featherRadius(
            stroke.selection, bitmap.width, bitmap.height, stroke.layerScale,
        )
        val paintClip = SelectionMask.paintClip(clipPath, featherRadius)
        // Distort/Warp: push the whole layer's pixels onto a moved handle grid. Before the tool
        // switch and before the selection work, because it deforms the layer rather than painting
        // into it — there is nothing here for a selection to clip.
        stroke.warpHandles?.let { handles ->
            val inBitmap = ImageProcessor.mapScreenToBitmap(
                handles, stroke.canvasSize.width, stroke.canvasSize.height,
                bitmap.width, bitmap.height,
                stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
            )
            return ImageWarp.warp(bitmap, inBitmap) ?: bitmap
        }
        // Colour Fill: flood the selection with a colour. Beside clearAll because they are the same
        // operation with a different source — one paints transparency, the other a colour — and both
        // are recorded onto a tool rather than being a kind of paint.
        if (stroke.fillSelection) {
            val target = SafeBitmap.copy(bitmap) ?: return bitmap
            val canvas = android.graphics.Canvas(target)
            SelectionMask.clip(canvas, paintClip)
            canvas.drawColor(stroke.brushColor, android.graphics.PorterDuff.Mode.SRC_OVER)
            return SelectionMask.feather(bitmap, target, clipPath, featherRadius)
        }
        // Clear: wipe to transparency, inside the selection if there is one. Checked before the
        // tool switch because it is an operation recorded onto a tool, not a kind of paint.
        if (stroke.clearAll) {
            val target = SafeBitmap.copy(bitmap) ?: return bitmap
            val canvas = android.graphics.Canvas(target)
            SelectionMask.clip(canvas, paintClip)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            return SelectionMask.feather(bitmap, target, clipPath, featherRadius)
        }
        // A selection move: lift the selected pixels, clear the hole, stamp them down offset. Fully
        // determined by (base, selection, delta), so it replays through history like any stroke.
        if (stroke.tool == Tool.SELECT) {
            val delta = stroke.moveDelta ?: return bitmap
            if (clipPath == null) return bitmap
            val d = SelectionMask.mapDelta(
                delta, stroke.canvasSize.width, stroke.canvasSize.height, bitmap.width, bitmap.height,
                stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
            )
            return SelectionMask.moveRegion(bitmap, clipPath, d.x, d.y, featherRadius)
        }
        val mapped = ImageProcessor.mapScreenToBitmap(
            stroke.path, stroke.canvasSize.width, stroke.canvasSize.height, bitmap.width, bitmap.height,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ
        )
        // Flood fill replays deterministically from its tap point — same base, same seed pixel,
        // same result — so it records and undoes exactly like a brush stroke.
        if (stroke.tool == Tool.FILL) {
            val target = SafeBitmap.copy(bitmap) ?: return bitmap
            val p = mapped.firstOrNull() ?: return target
            // The fill keeps the HARD region even when feathering: a scanline spread needs a yes
            // or a no about each pixel, and cannot flow into a gradient. The feather then softens
            // the result inwards from that boundary rather than across it.
            ImageProcessor.floodFill(
                target, p.x.toInt(), p.y.toInt(), stroke.brushColor,
                clipRegion = SelectionMask.region(clipPath, target.width, target.height),
            )
            return SelectionMask.feather(bitmap, target, clipPath, featherRadius)
        }
        // brushSize is stored in screen px (what the rail size preview shows). Convert it to bitmap
        // space with the same scale the coordinates use, so the painted stroke renders at exactly the
        // previewed on-screen diameter regardless of the layer's resolution/scale.
        val brushScale = ImageProcessor.screenToBitmapScale(
            stroke.canvasSize.width, stroke.canvasSize.height, bitmap.width, bitmap.height, stroke.layerScale
        )
        // Azphalt stamp-brush stroke: replay through the stamp renderer onto a fresh copy, using the
        // stroke's stored seed so the re-composited pixels match the original commit exactly.
        stroke.stampBrush?.let { brush ->
            // Bitmap.copy can return null under memory pressure — fall back to the input rather than NPE.
            val target = SafeBitmap.copy(bitmap) ?: return bitmap
            val pts = ArrayList<Float>(mapped.size * 2)
            mapped.forEach { pts.add(it.x); pts.add(it.y) }
            val stampCanvas = android.graphics.Canvas(target)
            SelectionMask.clip(stampCanvas, paintClip)
            StampBrushRenderer.paintStroke(
                stampCanvas, pts, brush, stroke.brushColor,
                stroke.brushSize * brushScale, stroke.flow, stroke.seed, stroke.stampShape,
            )
            return SelectionMask.feather(bitmap, target, clipPath, featherRadius)
        }
        return SelectionMask.feather(
            bitmap,
            ImageProcessor.applyToolToBitmap(
                bitmap, mapped, stroke.tool, stroke.brushSize * brushScale, stroke.brushColor, stroke.intensity,
                // Never mutate in place while feathering: the feather composites the painted result
                // back against the untouched base, and in-place painting would have already
                // destroyed the very pixels it needs to blend towards.
                replaceExisting && featherRadius <= 0f, stroke.feathering,
                alphaLock = stroke.alphaLock, symmetryMode = stroke.symmetryMode,
                clipPath = paintClip,
                opacity = stroke.opacity,
                pressures = stroke.pressures,
                // Screen-space offset carried through the same affine the coordinates take, as a
                // difference of two mapped points — that applies the scale and the layer rotation
                // while cancelling the translation, which a scalar multiply would get wrong on a
                // rotated layer.
                cloneOffset = stroke.cloneOffset?.let { off ->
                    SelectionMask.mapDelta(
                        off, stroke.canvasSize.width, stroke.canvasSize.height,
                        bitmap.width, bitmap.height,
                        stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
                    )
                },
            ),
            clipPath, featherRadius,
        )
    }

    /**
     * Liquify: push the layer's pixels around under the brush, then bake.
     *
     * **Confined to the selection like everything else.** It used to ignore it outright — lasso a
     * region, pick Liquify, drag, and the whole layer warped — while the rail advertised that a
     * region "confines every raster tool until it's cleared". The warp itself cannot be clipped,
     * because the native pass owns the whole bitmap; so the result is composited back through the
     * same [SelectionMask.feather] every other tool ends with, which keeps the warped pixels inside
     * the region and the original ones outside it. At radius 0 that is the hard-edged path, and with
     * no selection at all it is a straight copy, so the unselected case is unchanged.
     */
    private suspend fun applyLiquify(bitmap: Bitmap, stroke: StrokeCommand): Bitmap {
        slamManager.prepareLiquify(bitmap)
        val mapped = ImageProcessor.mapScreenToBitmap(
            stroke.path, stroke.canvasSize.width, stroke.canvasSize.height, bitmap.width, bitmap.height,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ
        )
        val flatArr = FloatArray(mapped.size * 2)
        mapped.forEachIndexed { i, pt -> flatArr[i * 2] = pt.x; flatArr[i * 2 + 1] = pt.y }
        slamManager.applyLiquify(flatArr, stroke.brushSize, 0.5f)
        val baked = SafeBitmap.copy(bitmap) ?: return bitmap
        slamManager.bakeLiquify(baked)
        val clipPath = SelectionMask.bitmapPath(
            stroke.selection, bitmap.width, bitmap.height,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
        )
        val featherRadius = SelectionMask.featherRadius(
            stroke.selection, bitmap.width, bitmap.height, stroke.layerScale,
        )
        return SelectionMask.confine(bitmap, baked, clipPath, featherRadius)
    }
}
