package com.hereliesaz.graffitixr.feature.editor.ink

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
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
     * Hard ([feathering] 0, the legacy round's default): [StockBrushes.pressurePen] rather than
     * `marker` — the built-in Round maps pressure to size (25–100%), and pressurePen is the stock
     * family with that same pressure → width response; a finger reports no pressure and gets a
     * constant width, as Round does. [opacity] (the Brush's whole-stroke opacity) is folded into the
     * colour's alpha, the only place a stock family takes it.
     *
     * Soft ([feathering] > 0): [softRoundFamily], a particle tip stamping [InkSoftRound]'s solved
     * falloff texture, widened by [InkSoftRound.tipScale] so the fade can reach past the nominal
     * edge as the legacy round's BlurMaskFilter does. Opacity lives in the texture there (see
     * [InkSoftRound.solveStampProfile]), so the colour keeps its own alpha.
     *
     * [sizeWorld] is the nominal diameter in world units — `EditorUiState.effectivePaintBrushSize()`,
     * the same number a round-brush [com.hereliesaz.graffitixr.feature.editor.StrokeCommand] records.
     */
    fun roundBrush(sizeWorld: Float, argb: Int, opacity: Float, feathering: Float = 0f): Brush {
        val size = sizeWorld.coerceAtLeast(MIN_SIZE)
        if (!InkSoftRound.isSoft(feathering)) {
            val alpha = ((argb ushr ALPHA_SHIFT and BYTE) * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, BYTE)
            val color = (alpha shl ALPHA_SHIFT) or (argb and RGB_MASK)
            return Brush.createWithColorIntArgb(
                family = StockBrushes.pressurePen(),
                colorIntArgb = color,
                size = size,
                epsilon = EPSILON,
            )
        }
        return Brush.createWithColorIntArgb(
            family = softRoundFamily(feathering, opacity),
            colorIntArgb = argb,
            size = size * InkSoftRound.tipScale(feathering),
            epsilon = EPSILON,
        )
    }

    /** A round particle tip stamping [InkSoftRound]'s solved texture, pressure → size 25–100%. */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun softRoundFamily(feathering: Float, opacity: Float): BrushFamily {
        val pressureToSize = BrushBehavior(
            listOf(
                BrushBehavior.TargetNode(
                    BrushBehavior.Target.SIZE_MULTIPLIER, MIN_PRESSURE_SIZE, 1f,
                    BrushBehavior.SourceNode(BrushBehavior.Source.NORMALIZED_PRESSURE, 0f, 1f),
                ),
            ),
        )
        val tip = BrushTip.Builder()
            .setCornerRounding(1f)
            .setParticleGapDistanceScale(InkSoftRound.PARTICLE_GAP)
            .setBehaviors(listOf(pressureToSize))
            .build()
        val texture = BrushPaint.TextureLayer.builder(InkSoftRound.textureId(feathering, opacity), 1f, 1f)
            .setSizeUnit(BrushPaint.TextureSizeUnit.BRUSH_SIZE)
            .setMapping(BrushPaint.TextureMapping.STAMPING)
            .build()
        return BrushFamily(tip, BrushPaint(listOf(texture)))
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
        val renderer = CanvasStrokeRenderer.create(InkSoftRoundTextures)
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

    private const val ALPHA_SHIFT = 24
    private const val BYTE = 0xFF
    private const val RGB_MASK = 0xFFFFFF
    private const val MIN_SIZE = 0.5f
    /** The legacy round's pressure → size floor (BuiltInBrushes.round's 0.25). */
    private const val MIN_PRESSURE_SIZE = 0.25f
    /** Ink's geometric tolerance in world units; 0.1 is the value Ink's own samples use. */
    private const val EPSILON = 0.1f
}
