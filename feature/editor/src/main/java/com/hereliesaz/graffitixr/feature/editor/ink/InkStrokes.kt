package com.hereliesaz.graffitixr.feature.editor.ink

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.MutableVec
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.hereliesaz.graffitixr.common.model.InkUtensil

/**
 * The Jetpack Ink half of the Ink brush path that touches Ink's native library: building the Ink
 * [Brush] for an [InkUtensil] from the editor's brush settings, rendering a finished [Stroke] into a layer bitmap, and
 * reading a stroke's outline geometry back out. Everything that can be pure Kotlin (coordinate
 * mapping, SVG text, the per-layer ledger) lives beside it in this package instead, so it is
 * unit-testable without the native library.
 */
internal object InkStrokes {

    /**
     * The stock [BrushFamily] behind [utensil] — the one place an [InkUtensil] becomes Ink.
     *
     * Every family is pinned to its `V1` version rather than `LATEST`: a family's `LATEST` is allowed
     * to change shape in a later Ink release, and a stroke re-rendered on undo/redo/bake (or by a
     * co-op peer on another Ink version) should look like the one that was drawn.
     */
    fun family(utensil: InkUtensil): BrushFamily = when (utensil) {
        InkUtensil.PEN -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        InkUtensil.MARKER -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
        InkUtensil.HIGHLIGHTER -> StockBrushes.highlighter(
            SelfOverlap.DISCARD,
            StockBrushes.HighlighterVersion.V1,
        )
        InkUtensil.DASHED_LINE -> StockBrushes.dashedLine(StockBrushes.DashedLineVersion.V1)
    }

    /**
     * The Ink [Brush] for [utensil] at the editor's current settings.
     *
     * [sizeWorld] is the nominal diameter in world units — `EditorUiState.effectivePaintBrushSize()`,
     * the same number every Brush-tool [com.hereliesaz.graffitixr.feature.editor.StrokeCommand]
     * records. [opacity] (the Brush's whole-stroke opacity) is folded into the colour's alpha, the
     * only place a stock family takes it. There is deliberately no feathering parameter: the stock
     * families have fixed tips (see [InkUtensil]).
     */
    fun brush(utensil: InkUtensil, sizeWorld: Float, argb: Int, opacity: Float): Brush =
        Brush.createWithColorIntArgb(
            family = family(utensil),
            colorIntArgb = InkColor.withOpacity(argb, opacity),
            size = sizeWorld.coerceAtLeast(MIN_SIZE),
            epsilon = EPSILON,
        )

    /**
     * Rebuilds an Ink stroke from bare [points] (interleaved x, y) and [pressures] (one per point,
     * empty = none) — what a co-op guest receives. Ink needs non-decreasing timestamps and the wire
     * carries none, so the inputs are spaced [SYNTHETIC_SAMPLE_MS] apart; the stock families shape
     * by distance, not time, so the spacing changes nothing a guest can see.
     */
    fun strokeFromPoints(brush: Brush, points: List<Float>, pressures: List<Float>): Stroke {
        val batch = MutableStrokeInputBatch()
        val count = points.size / 2
        for (i in 0 until count) {
            val pressure = pressures.getOrNull(i)?.coerceIn(0f, 1f) ?: StrokeInput.NO_PRESSURE
            batch.add(
                InputToolType.TOUCH, points[i * 2], points[i * 2 + 1], i * SYNTHETIC_SAMPLE_MS,
                StrokeInput.NO_STROKE_UNIT_LENGTH, pressure,
            )
        }
        return Stroke(brush, batch)
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
        for (group in 0 until mesh.getRenderGroupCount()) {
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

    private const val MIN_SIZE = 0.5f
    /** Spacing of a co-op stroke's synthetic input timestamps (~120 Hz). */
    private const val SYNTHETIC_SAMPLE_MS = 8L
    /** Ink's geometric tolerance in world units; 0.1 is the value Ink's own samples use. */
    private const val EPSILON = 0.1f
}
