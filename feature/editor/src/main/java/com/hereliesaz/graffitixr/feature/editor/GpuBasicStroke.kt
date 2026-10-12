package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
import com.hereliesaz.graffitixr.common.model.CatmullRom
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController
import com.hereliesaz.graffitixr.nativebridge.BrushDab
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import com.hereliesaz.graffitixr.nativebridge.ResolvedBrushDab
import kotlin.math.max

/** How a Basic Brush stroke's round dabs are painted: colour, whole-stroke opacity, edge. */
internal data class BasicPaint(val colorArgb: Int, val flow: Float, val hardness: Float) {
    companion object {
        /**
         * [flow] is the colour's own alpha times the stroke's opacity ceiling, as the CPU replay
         * composites it; feathering softens the dab edge ([hardness] 1 = hard).
         */
        fun of(colorArgb: Int, opacity: Float, feathering: Float) = BasicPaint(
            colorArgb = colorArgb,
            flow = (colorArgb ushr ALPHA_SHIFT and BYTE_MASK) / BYTE_MAX * opacity.coerceIn(0f, 1f),
            hardness = (1f - feathering).coerceIn(0f, 1f),
        )

        private const val ALPHA_SHIFT = 24
        private const val BYTE_MASK = 0xFF
        private const val BYTE_MAX = 255f
    }
}

/**
 * Basic Brush on the GPU (wgpu). The live stroke places one run of round dabs per Catmull-Rom
 * segment (EditorViewModel.drawCurveRun); [runs] places exactly those from a recorded stroke, and
 * the commit and undo/redo replay stamp them the same way the live stroke did. Dabs combine by
 * stroke-wide max (`strokeMax`), so overlapping segments never stack opacity: the same
 * whole-stroke opacity ceiling as the CPU replay.
 */
internal object GpuBasicStroke {

    private const val TAG = "GpuBasicStroke"

    /**
     * One dab run per segment of [mapped] (layer-bitmap points), tiled 3x3 under wrap-around, as
     * the live stroke places them. A single point is one dab.
     */
    @Suppress("LongParameterList")
    fun runs(
        mapped: List<Offset>,
        baseWidth: Float,
        pressures: List<Float>,
        wrapAroundMode: Boolean,
        width: Int,
        height: Int,
    ): List<List<BrushDab>> {
        val tile = { centres: List<Offset>, radius: Float -> tiled(centres, radius, wrapAroundMode, width, height) }
        if (mapped.size < 2) return mapped.map { tile(listOf(it), max(baseWidth / 2f, MIN_RADIUS)) }
        val widths = BrushDynamics.segmentWidths(mapped, baseWidth, pressures)
        val flat = ArrayList<Float>(mapped.size * 2)
        mapped.forEach { flat.add(it.x); flat.add(it.y) }
        val segments = CatmullRom.segments(flat)
        return (0 until mapped.size - 1).map { i ->
            val radius = max(widths[i] / 2f, MIN_RADIUS)
            val centres = BrushStamps.place(segments[i].toList(), max(radius * ROUND_BRUSH_DAB_SPACING_FRACTION, 1f))
            tile((centres.indices step 2).map { Offset(centres[it], centres[it + 1]) }, radius)
        }
    }

    /** Round dabs at [centres], each repeated at the 3x3 neighbouring tiles under wrap-around. */
    private fun tiled(centres: List<Offset>, radius: Float, wrap: Boolean, width: Int, height: Int): List<BrushDab> {
        if (!wrap) return centres.map { BrushDab(it.x, it.y, radius, 1f, 0f) }
        val out = ArrayList<BrushDab>(centres.size * TILE_COPIES)
        for (p in centres) {
            for (dx in -1..1) for (dy in -1..1) out.add(BrushDab(p.x + dx * width, p.y + dy * height, radius, 1f, 0f))
        }
        return out
    }

    /** [runs] stamped onto [engine]'s layer. Doesn't read back. */
    fun stampRuns(engine: GpuStampEngine, runs: List<List<BrushDab>>, paint: BasicPaint): Boolean =
        runs.all { run ->
            run.isEmpty() || engine.stampResolvedDabs(
                run.map {
                    ResolvedBrushDab(
                        x = it.x, y = it.y, radius = it.radius, alpha = 1f, angleDeg = 0f,
                        colorArgb = paint.colorArgb, flow = paint.flow, hardness = paint.hardness,
                    )
                },
                buildUp = false,
                strokeMax = true,
            )
        }

    /**
     * A recorded Basic Brush stroke painted onto a copy of [bitmap] on the GPU, unclipped; null
     * when the GPU is unavailable (the caller then takes the CPU renderer).
     */
    fun paintStroke(bitmap: Bitmap, stroke: StrokeCommand, mapped: List<Offset>, brushScale: Float): Bitmap? {
        val target = SafeBitmap.copy(bitmap)
        val runs = runs(
            mapped, stroke.brushSize * brushScale, stroke.pressures, stroke.wrapAroundMode,
            bitmap.width, bitmap.height,
        )
        val paint = BasicPaint.of(stroke.brushColor, stroke.opacity, stroke.feathering)
        val painted = target != null && render(target, runs, paint)
        if (!painted) target?.recycle()
        return target.takeIf { painted }
    }

    private fun render(target: Bitmap, runs: List<List<BrushDab>>, paint: BasicPaint): Boolean {
        // Calibration's engines and the canvas's never coexist (GpuCalibration.stopForCanvas);
        // this runs on a commit worker, never on the touch path.
        GpuTuningController.stopCalibrationForCanvas()
        val engine = runCatching { GpuStampEngine() }.getOrNull()
        return engine != null && try {
            engine.init(target.width, target.height) &&
                engine.upload(target) &&
                stampRuns(engine, runs, paint) &&
                engine.readback(target)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            Log.w(TAG, "GPU Basic Brush commit failed; the CPU renderer takes this stroke", e)
            false
        } finally {
            engine.destroy()
        }
    }

    private const val MIN_RADIUS = 0.5f
    private const val TILE_COPIES = 9
}
