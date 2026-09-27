package com.hereliesaz.graffitixr.feature.editor.ink

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.MutableVec
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

/**
 * The Jetpack Ink half of the Ink brush path that touches Ink's native library: building the Ink
 * [Brush] from the editor's brush settings, rendering a finished [Stroke] into a layer bitmap, and
 * reading a stroke's outline geometry back out. Everything that can be pure Kotlin (coordinate
 * mapping, SVG text, the per-layer ledger) lives beside it in this package instead, so it is
 * unit-testable without the native library.
 */
internal object InkStrokes {

    /**
     * The Ink brush for the editor's round Brush.
     *
     * [StockBrushes.pressurePen] rather than `marker`: the built-in Round maps pressure to size
     * (25–100%), and pressurePen is the stock family with that same pressure → width response; a
     * finger reports no pressure and gets a constant width, as Round does. [sizeWorld] is the
     * diameter in world units — `EditorUiState.effectivePaintBrushSize()`, the same number a
     * round-brush [com.hereliesaz.graffitixr.feature.editor.StrokeCommand] records — and
     * [opacity] (the Brush's whole-stroke opacity) is folded into the colour's alpha, the only
     * place Ink takes it.
     *
     * Not mapped: hardness. Stock Ink families have hard, anti-aliased edges and Ink 1.0 exposes
     * no tip-softness control short of authoring a custom `BrushFamily`, so a soft Round draws hard
     * on this path. Round's own 0.85 hardness is close enough to read as the same brush.
     */
    fun roundBrush(sizeWorld: Float, argb: Int, opacity: Float): Brush {
        val alpha = ((argb ushr ALPHA_SHIFT and BYTE) * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, BYTE)
        val color = (alpha shl ALPHA_SHIFT) or (argb and RGB_MASK)
        return Brush.createWithColorIntArgb(
            family = StockBrushes.pressurePen(),
            colorIntArgb = color,
            size = sizeWorld.coerceAtLeast(MIN_SIZE),
            epsilon = EPSILON,
        )
    }

    /**
     * Renders [stroke] (in world coordinates) onto [canvas] (a layer bitmap's canvas, already
     * clipped to the selection by the caller) through [worldToBitmap].
     *
     * [alphaLock] composites the stroke through an offscreen layer with SRC_ATOP, so it paints only
     * where the layer already has alpha — the same rule the round brush's SRC_ATOP paint applies.
     * [wrapAround] draws the stroke nine times, offset by one canvas in each direction, exactly as
     * `ImageProcessor.drawStroke` tiles a wrapped path.
     */
    fun draw(
        canvas: Canvas,
        stroke: Stroke,
        worldToBitmap: InkAffine,
        alphaLock: Boolean,
        wrapAround: Boolean,
    ) {
        // A renderer per commit: DrawingEngine can run on several default-dispatcher threads at once
        // (one per layer being rebuilt) and a renderer's paint caches are not documented as
        // thread-safe. Creating one is cheap next to the full-layer copy every commit already makes.
        val renderer = CanvasStrokeRenderer.create()
        val saved = if (alphaLock) {
            canvas.saveLayer(null, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) })
        } else {
            null
        }
        val matrix = Matrix()
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val tiles = if (wrapAround) -1..1 else 0..0
        for (dx in tiles) {
            for (dy in tiles) {
                matrix.setValues(worldToBitmap.translated(dx * w, dy * h).toMatrixValues())
                renderer.draw(canvas, stroke, matrix)
            }
        }
        saved?.let(canvas::restoreToCount)
    }

    /**
     * [stroke]'s outline loops, mapped through [transform] — one flat `[x0, y0, x1, y1, …]` array
     * per outline, across every render group of its `PartitionedMesh` shape. What the SVG export
     * turns into `<path>` data.
     */
    fun outlines(stroke: Stroke, transform: InkAffine): List<FloatArray> {
        val mesh = stroke.shape
        val scratch = MutableVec()
        val result = ArrayList<FloatArray>()
        for (group in 0 until mesh.renderGroupCount) {
            for (outline in 0 until mesh.getOutlineCount(group)) {
                val count = mesh.getOutlineVertexCount(group, outline)
                val points = FloatArray(count * 2)
                for (i in 0 until count) {
                    mesh.populateOutlinePosition(group, outline, i, scratch)
                    points[i * 2] = transform.mapX(scratch.x, scratch.y)
                    points[i * 2 + 1] = transform.mapY(scratch.x, scratch.y)
                }
                result += points
            }
        }
        return result
    }

    private const val ALPHA_SHIFT = 24
    private const val BYTE = 0xFF
    private const val RGB_MASK = 0xFFFFFF
    private const val MIN_SIZE = 0.5f
    /** Ink's geometric tolerance in world units; 0.1 is the value Ink's own samples use. */
    private const val EPSILON = 0.1f
}
