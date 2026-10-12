package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.GrainBehavior
import com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import com.hereliesaz.graffitixr.nativebridge.MaskedBrushDab
import com.hereliesaz.graffitixr.nativebridge.ResolvedBrushDab
import com.hereliesaz.graffitixr.nativebridge.SecondaryBrushDab

/** How a stamp stroke's dabs are coloured and textured: everything but the dabs themselves. */
internal data class StampPaint(
    val brush: AzphaltBrush,
    val colorArgb: Int,
    val secondaryColorArgb: Int,
    val flow: Float,
    val shape: Bitmap?,
    val grain: Bitmap?,
    val maskShape: Bitmap?,
    val seed: Long,
) {
    companion object {
        fun of(stroke: StrokeCommand, brush: AzphaltBrush) = StampPaint(
            brush, stroke.brushColor, stroke.secondaryBrushColor, stroke.flow,
            stroke.stampShape, stroke.stampGrain, stroke.stampMaskShape, stroke.seed,
        )
    }
}

/**
 * Stamp-brush paint on the GPU (wgpu), through the same shaders and per-dab resolution as the live
 * stroke (EditorViewModel.renderStampLive). [stampOnto] finishes a live stroke on its own engine;
 * [paintStroke] renders a recorded stroke from scratch for undo/redo replay (and for a commit whose
 * live stroke wasn't on the GPU). Each batch stamps its movement dabs, then its held (airbrush) dabs
 * with build-up on, in the order the live stroke did.
 */
internal object GpuStampCommit {

    private const val TAG = "GpuStampCommit"

    /**
     * [stroke] painted onto a copy of [bitmap] on the GPU, unclipped; null when the GPU is
     * unavailable. Same dab lists as DrawingEngine's CPU branch ([StrokeDabs]), so the two only
     * ever differ in the rasterizer. The caller excludes impasto and substrate strokes, whose CPU
     * material state the GPU commit doesn't model.
     */
    fun paintStroke(
        bitmap: Bitmap,
        stroke: StrokeCommand,
        brush: AzphaltBrush,
        mapped: List<Offset>,
        brushScale: Float,
    ): Bitmap? {
        val target = SafeBitmap.copy(bitmap)
        val width = bitmap.width
        val height = bitmap.height
        val (movementDabs, heldDabs) = StrokeDabs.recorded(stroke, brush, mapped, brushScale, width, height)
            ?: (StrokeDabs.fromPath(stroke, brush, mapped, brushScale, width, height) to emptyList())
        val batches = StrokeDabs.batches(movementDabs, heldDabs, stroke.stampBatches)
        val painted = target != null && render(target, batches, StampPaint.of(stroke, brush))
        if (!painted) target?.recycle()
        return target.takeIf { painted }
    }

    /** [painted] kept only inside [clip]; [base]'s pixels everywhere else. No clip: [painted]. */
    fun keepInside(base: Bitmap, painted: Bitmap, clip: Path?): Bitmap {
        val out = clip?.let { SafeBitmap.copy(base) } ?: return painted
        val canvas = Canvas(out)
        SelectionMask.clip(canvas, clip)
        canvas.drawBitmap(painted, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        painted.recycle()
        return out
    }

    /**
     * [batches] stamped onto [target] on a fresh engine, then read back once. False when the GPU is
     * unavailable or a stage fails; [target] is then left partly painted.
     */
    private fun render(target: Bitmap, batches: List<Pair<List<Dab>, List<Dab>>>, paint: StampPaint): Boolean {
        if (batches.all { (m, h) -> m.isEmpty() && h.isEmpty() }) return true
        // Calibration's engines and the canvas's never coexist (GpuCalibration.stopForCanvas).
        // This runs on a commit worker, never on the touch path, so waiting here delays only the
        // commit, not the stroke on screen.
        GpuTuningController.stopCalibrationForCanvas()
        val engine = runCatching { GpuStampEngine() }.getOrNull()
        return engine != null && try {
            engine.init(target.width, target.height) &&
                engine.upload(target) &&
                stampOnto(engine, batches, paint) &&
                engine.readback(target)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            Log.w(TAG, "GPU stamp commit failed; the CPU renderer takes this stroke", e)
            false
        } finally {
            engine.destroy()
        }
    }

    /** [batches] stamped onto [engine]'s layer in order. Doesn't read back. */
    fun stampOnto(engine: GpuStampEngine, batches: List<Pair<List<Dab>, List<Dab>>>, paint: StampPaint): Boolean {
        val brush = paint.brush
        val baseFlow = paint.flow.coerceIn(0f, 1f)
        if (gpuPipelineUsesMaskedShader(brush, paint.shape, paint.grain)) {
            val stamper = MaskedStamper(engine, paint, baseFlow)
            return batches.all { (movement, held) -> stamper.stampAll(movement, held) }
        }
        fun resolve(dab: Dab) = ResolvedBrushDab(
            x = dab.x,
            y = dab.y,
            radius = dab.radius,
            alpha = dab.alpha,
            angleDeg = dab.angleDeg,
            colorArgb = StampBrushRenderer.resolvedColor(paint.colorArgb, paint.secondaryColorArgb, brush, dab),
            flow = (baseFlow * dab.flowMultiplier).coerceAtLeast(0f),
            hardness = dab.hardness,
            contactDepth = dab.contactDepth,
        )
        return batches.all { (movement, held) ->
            (movement.isEmpty() ||
                engine.stampResolvedDabs(movement.map(::resolve), buildUp = brush.buildUp, strokeMax = true)) &&
                (held.isEmpty() || engine.stampResolvedDabs(held.map(::resolve), buildUp = true))
        }
    }
}

/** The masked-shader half of [GpuStampCommit]: tip, grain and secondary masks resolved once. */
private class MaskedStamper(
    private val engine: GpuStampEngine,
    paint: StampPaint,
    private val baseFlow: Float,
) {
    private val brush = paint.brush
    private val colorArgb = paint.colorArgb
    private val secondaryColorArgb = paint.secondaryColorArgb
    private val mask = alphaChannelBytes(
        BrushTipMaskCache.tipMask(paint.shape, GPU_MASK_REFERENCE_SIZE, GPU_MASK_REFERENCE_SIZE, brush.hardness),
    )
    private val grainResolution = resolveGrainTileAndPhase(paint.grain, brush, paint.seed)
    private val grainAlpha8 = grainResolution.tile?.let(::alphaChannelBytes)
    private val secondaryMask = brush.maskedBrush?.let {
        alphaChannelBytes(
            BrushTipMaskCache.tipMask(paint.maskShape, GPU_MASK_REFERENCE_SIZE, GPU_MASK_REFERENCE_SIZE, it.hardness),
        )
    }
    private val secondaryMaskSize = if (secondaryMask != null) GPU_MASK_REFERENCE_SIZE else 0

    /** Held dabs carry no secondary geometry live either (renderStampLive's secondaryHeldDabs). */
    fun stampAll(movementDabs: List<Dab>, heldDabs: List<Dab>): Boolean =
        stamp(movementDabs, withSecondary = true) && stamp(heldDabs, withSecondary = false)

    private fun masked(dab: Dab) = MaskedBrushDab(
        x = dab.x,
        y = dab.y,
        radius = dab.radius,
        alpha = dab.alpha,
        angleDeg = dab.angleDeg,
        colorArgb = StampBrushRenderer.resolvedColor(colorArgb, secondaryColorArgb, brush, dab),
        flow = (baseFlow * dab.flowMultiplier).coerceAtLeast(0f),
        tipRatio = dab.tipRatio,
        contactDepth = dab.contactDepth,
    )

    /** Null when a dual-tip brush's dabs lost their secondary geometry: not drawable faithfully. */
    private fun secondary(dabs: List<Dab>): List<SecondaryBrushDab>? = when {
        secondaryMask == null -> emptyList()
        dabs.any { it.mask == null } -> null
        else -> dabs.mapNotNull { it.mask }.map { m ->
            SecondaryBrushDab(m.x, m.y, m.radius, m.tipRatio, m.alpha, m.angleDeg, m.flowMultiplier, m.keepInside)
        }
    }

    private fun stamp(dabs: List<Dab>, withSecondary: Boolean): Boolean {
        val secondaryDabs = if (withSecondary) secondary(dabs) else emptyList()
        return dabs.isEmpty() || secondaryDabs != null && engine.stampMaskedDabs(
            dabs.map(::masked), brush.hardness.coerceIn(0f, 1f), mask,
            GPU_MASK_REFERENCE_SIZE, GPU_MASK_REFERENCE_SIZE,
            grainAlpha8, grainResolution.tile?.width ?: 0, grainResolution.tile?.height ?: 0,
            brush.grainBehavior == GrainBehavior.CANVAS_LOCKED, brush.grainScale,
            grainResolution.phaseX, grainResolution.phaseY,
            secondaryDabs, secondaryMask, secondaryMaskSize, secondaryMaskSize,
        )
    }
}
