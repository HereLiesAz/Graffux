package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.LayerFrame
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController
import com.hereliesaz.graffitixr.nativebridge.GpuRetouch
import com.hereliesaz.graffitixr.nativebridge.GpuSelection
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine

/**
 * The retouch tools on the GPU. Every one paints the same round dabs at a constant width along the
 * stroke's polyline (GpuBasicStroke.eraserRuns: the round-capped line the CPU tools drew):
 *
 * * Burn and Dodge stamp black / white at `intensity * 0.3` with the round shader's DARKEN /
 *   LIGHTEN, Heal stamps the colour at half alpha over the layer, and Color washes it over at
 *   `intensity` with SRC_ATOP -- ImageProcessor's Paint xfermodes, combined by stroke-wide max so a
 *   stroke never builds up over itself.
 * * Blur, Sharpen and Clone read their neighbourhood (GpuRetouch): the engine keeps the layer as
 *   the stroke found it and writes the tool's result from that.
 */
internal object GpuRetouchStroke {

    /** The tools this paints. */
    val TOOLS = setOf(Tool.BLUR, Tool.SHARPEN, Tool.CLONE, Tool.HEAL, Tool.BURN, Tool.DODGE, Tool.COLOR)

    /** The paint a stamped retouch tool uses, null for the neighbourhood tools. */
    fun paint(stroke: StrokeCommand): BasicPaint? = paint(stroke.tool, stroke.brushColor, stroke.intensity)

    /** The paint [tool] stamps with in [brushColor] at [intensity]; null unless it is a stamped retouch tool. */
    fun paint(tool: Tool, brushColor: Int, intensity: Float): BasicPaint? {
        val opaque = brushColor or OPAQUE
        val level = intensity.coerceIn(0f, 1f)
        return when (tool) {
            Tool.BURN -> BasicPaint(Color.BLACK, level * TONE_STRENGTH, 1f, GpuStampEngine.STAMP_DARKEN)
            Tool.DODGE -> BasicPaint(Color.WHITE, level * TONE_STRENGTH, 1f, GpuStampEngine.STAMP_LIGHTEN)
            Tool.HEAL -> BasicPaint(opaque, HEAL_ALPHA, 1f)
            Tool.COLOR -> BasicPaint(opaque, level, 1f, GpuStampEngine.STAMP_ATOP)
            else -> null
        }
    }

    /**
     * [stroke] painted onto [engine]'s layer ([width] x [height]). [cloneOffset] is Clone's source
     * vector in layer pixels.
     */
    @Suppress("LongParameterList")
    fun paintOnto(
        engine: GpuStampEngine,
        stroke: StrokeCommand,
        mapped: List<Offset>,
        brushScale: Float,
        width: Int,
        height: Int,
        cloneOffset: Offset?,
        frame: LayerFrame? = null,
    ): Boolean {
        val logicalWidth = stroke.wrapFrameWidth.takeIf { it > 0 } ?: frame?.width ?: width
        val logicalHeight = stroke.wrapFrameHeight.takeIf { it > 0 } ?: frame?.height ?: height
        val runs = GpuBasicStroke.eraserRuns(
            mapped, stroke.brushSize * brushScale, stroke.wrapAroundMode, logicalWidth, logicalHeight,
        )
        paint(stroke)?.let { return GpuBasicStroke.stampRuns(engine, runs, it) }
        return neighbourhood(
            engine, stroke.tool, runs.flatten(),
            Neighbourhood(stroke.intensity, stroke.feathering, stroke.wrapAroundMode, frame), cloneOffset,
        )
    }

    /** A neighbourhood tool's settings: [intensity], the edge [feathering], and Clone's [wrap]. */
    class Neighbourhood(
        val intensity: Float,
        val feathering: Float,
        val wrap: Boolean,
        val frame: LayerFrame? = null,
    )

    /**
     * Blur, Sharpen or Clone ([tool]) over [dabs] on [engine], from the layer as it is now (taken as
     * the stroke's shadow). True for any other tool, which paints nothing here.
     */
    fun neighbourhood(
        engine: GpuStampEngine,
        tool: Tool,
        dabs: List<com.hereliesaz.graffitixr.nativebridge.BrushDab>,
        settings: Neighbourhood,
        cloneOffset: Offset?,
    ): Boolean {
        val hardness = (1f - settings.feathering).coerceIn(0f, 1f)
        val flat = FloatArray(dabs.size * GpuRetouch.DAB_FLOATS)
        dabs.forEachIndexed { i, d ->
            val o = i * GpuRetouch.DAB_FLOATS
            flat[o] = d.x
            flat[o + 1] = d.y
            flat[o + D_RADIUS] = d.radius
            flat[o + D_HARDNESS] = hardness
        }
        val intensity = settings.intensity.coerceIn(0f, 1f)
        val retouch = GpuRetouch(engine)
        return dabs.isEmpty() || GpuSelection(engine).takeShadow() && when (tool) {
            Tool.BLUR -> retouch.run(GpuRetouch.BLUR, flat, factor = blurFactor(intensity))
            Tool.SHARPEN -> retouch.run(GpuRetouch.SHARPEN, flat, amount = SHARPEN_BASE + intensity * SHARPEN_RANGE)
            Tool.CLONE -> cloneOffset == null || retouch.run(
                GpuRetouch.CLONE, flat, offsetX = cloneOffset.x, offsetY = cloneOffset.y, wrap = settings.wrap,
                wrapFrameLeft = -(settings.frame?.originX ?: 0),
                wrapFrameTop = -(settings.frame?.originY ?: 0),
                wrapFrameWidth = settings.frame?.width ?: 0,
                wrapFrameHeight = settings.frame?.height ?: 0,
            )
            else -> true
        }
    }

    /** ImageProcessor's Blur downscale factor for [intensity]. */
    fun blurFactor(intensity: Float): Int = (BLUR_BASE + intensity * BLUR_RANGE).toInt().coerceIn(BLUR_BASE, BLUR_MAX)

    /**
     * [stroke] painted onto a copy of [bitmap] on a fresh engine, unclipped; null when a GPU stage
     * fails.
     */
    fun paintStroke(
        bitmap: Bitmap,
        stroke: StrokeCommand,
        mapped: List<Offset>,
        brushScale: Float,
        cloneOffset: Offset?,
        frame: LayerFrame? = null,
    ): Bitmap? {
        val target = SafeBitmap.copy(bitmap) ?: return null
        GpuTuningController.stopCalibrationForCanvas()
        val engine = runCatching { GpuStampEngine() }.getOrNull()
        val painted = engine != null && try {
            engine.init(target.width, target.height) && engine.upload(target) &&
                paintOnto(engine, stroke, mapped, brushScale, target.width, target.height, cloneOffset, frame) &&
                engine.readback(target)
        } finally {
            engine.destroy()
        }
        if (!painted) target.recycle()
        return target.takeIf { painted }
    }

    private const val OPAQUE = -0x1000000
    private const val D_RADIUS = 2
    private const val D_HARDNESS = 3
    private const val TONE_STRENGTH = 0.3f
    private const val HEAL_ALPHA = 128f / 255f
    private const val BLUR_BASE = 2
    private const val BLUR_RANGE = 12f
    private const val BLUR_MAX = 16
    private const val SHARPEN_BASE = 0.4f
    private const val SHARPEN_RANGE = 1.6f
}
