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
        applyTool(base, command, replaceExisting = false)

    private suspend fun applyTool(bitmap: Bitmap, stroke: StrokeCommand, replaceExisting: Boolean): Bitmap {
        // The lasso in force when this stroke was drawn, in this bitmap's pixel space. Every branch
        // below clips to it, so replaying a stroke reproduces the clipped paint exactly.
        val clipPath = SelectionMask.bitmapPath(
            stroke.selection, bitmap.width, bitmap.height,
            stroke.layerScale, stroke.layerOffset, stroke.layerRotationZ,
        )
        // Clear: wipe to transparency, inside the selection if there is one. Checked before the
        // tool switch because it is an operation recorded onto a tool, not a kind of paint.
        if (stroke.clearAll) {
            val target = SafeBitmap.copy(bitmap) ?: return bitmap
            val canvas = android.graphics.Canvas(target)
            SelectionMask.clip(canvas, clipPath)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            return target
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
            return SelectionMask.moveRegion(bitmap, clipPath, d.x, d.y)
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
            ImageProcessor.floodFill(
                target, p.x.toInt(), p.y.toInt(), stroke.brushColor,
                clipRegion = SelectionMask.region(clipPath, target.width, target.height),
            )
            return target
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
            SelectionMask.clip(stampCanvas, clipPath)
            StampBrushRenderer.paintStroke(
                stampCanvas, pts, brush, stroke.brushColor,
                stroke.brushSize * brushScale, stroke.flow, stroke.seed, stroke.stampShape,
            )
            return target
        }
        return ImageProcessor.applyToolToBitmap(
            bitmap, mapped, stroke.tool, stroke.brushSize * brushScale, stroke.brushColor, stroke.intensity,
            replaceExisting, stroke.feathering,
            alphaLock = stroke.alphaLock, symmetry = stroke.symmetry,
            clipPath = clipPath,
        )
    }

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
        return baked
    }
}
