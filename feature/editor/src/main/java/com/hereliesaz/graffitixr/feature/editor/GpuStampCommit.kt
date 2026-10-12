package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.GrainBehavior
import com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import com.hereliesaz.graffitixr.nativebridge.MaskedBrushDab
import com.hereliesaz.graffitixr.nativebridge.ResolvedBrushDab
import com.hereliesaz.graffitixr.nativebridge.SecondaryBrushDab

/**
 * A stamp-brush stroke's committed pixels, rendered on the GPU (wgpu): the stroke's canonical dab
 * list stamped through the same shaders, with the same per-dab resolution, as the live stroke
 * (EditorViewModel.renderStampLive), then read back once. Commit and undo/redo replay both reach
 * this through DrawingEngine, so the committed layer is the GPU's paint, not a CPU re-render.
 *
 * Movement dabs go in one call, held (airbrush) dabs in a second with build-up on, matching the
 * live split. [render] returns false when the GPU is unavailable or a stage fails; [target] is then
 * left partly painted and the caller must start over from the pristine base.
 */
internal object GpuStampCommit {

    @Suppress("LongParameterList")
    fun render(
        target: Bitmap,
        movementDabs: List<Dab>,
        heldDabs: List<Dab>,
        brush: AzphaltBrush,
        colorArgb: Int,
        secondaryColorArgb: Int,
        flow: Float,
        shape: Bitmap?,
        grain: Bitmap?,
        maskShape: Bitmap?,
        seed: Long,
    ): Boolean {
        if (movementDabs.isEmpty() && heldDabs.isEmpty()) return true
        // Calibration's engines and the canvas's never coexist (GpuCalibration.stopForCanvas).
        // This runs on a commit worker, never on the touch path, so waiting here delays only the
        // commit, not the stroke on screen.
        GpuTuningController.stopCalibrationForCanvas()
        val engine = runCatching { GpuStampEngine() }.getOrNull()
        return engine != null && try {
            engine.init(target.width, target.height) &&
                engine.upload(target) &&
                stampAll(
                    engine, movementDabs, heldDabs, brush, colorArgb, secondaryColorArgb,
                    flow, shape, grain, maskShape, seed,
                ) &&
                engine.readback(target)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            false
        } finally {
            engine.destroy()
        }
    }

    @Suppress("LongParameterList")
    private fun stampAll(
        engine: GpuStampEngine,
        movementDabs: List<Dab>,
        heldDabs: List<Dab>,
        brush: AzphaltBrush,
        colorArgb: Int,
        secondaryColorArgb: Int,
        flow: Float,
        shape: Bitmap?,
        grain: Bitmap?,
        maskShape: Bitmap?,
        seed: Long,
    ): Boolean {
        val baseFlow = flow.coerceIn(0f, 1f)
        if (!gpuPipelineUsesMaskedShader(brush, shape, grain)) {
            fun resolve(dab: Dab) = ResolvedBrushDab(
                x = dab.x,
                y = dab.y,
                radius = dab.radius,
                alpha = dab.alpha,
                angleDeg = dab.angleDeg,
                colorArgb = StampBrushRenderer.resolvedColor(colorArgb, secondaryColorArgb, brush, dab),
                flow = (baseFlow * dab.flowMultiplier).coerceAtLeast(0f),
                hardness = dab.hardness,
                contactDepth = dab.contactDepth,
            )
            return (movementDabs.isEmpty() ||
                engine.stampResolvedDabs(movementDabs.map(::resolve), buildUp = brush.buildUp, strokeMax = true)) &&
                (heldDabs.isEmpty() || engine.stampResolvedDabs(heldDabs.map(::resolve), buildUp = true))
        }
        return MaskedStamper(engine, brush, colorArgb, secondaryColorArgb, baseFlow, shape, grain, maskShape, seed)
            .stampAll(movementDabs, heldDabs)
    }
}

/** The masked-shader half of [GpuStampCommit]: tip, grain and secondary masks resolved once. */
@Suppress("LongParameterList")
private class MaskedStamper(
    private val engine: GpuStampEngine,
    private val brush: AzphaltBrush,
    private val colorArgb: Int,
    private val secondaryColorArgb: Int,
    private val baseFlow: Float,
    shape: Bitmap?,
    grain: Bitmap?,
    maskShape: Bitmap?,
    seed: Long,
) {
    private val mask = alphaChannelBytes(
        BrushTipMaskCache.tipMask(shape, GPU_MASK_REFERENCE_SIZE, GPU_MASK_REFERENCE_SIZE, brush.hardness),
    )
    private val grainResolution = resolveGrainTileAndPhase(grain, brush, seed)
    private val grainAlpha8 = grainResolution.tile?.let(::alphaChannelBytes)
    private val secondaryMask = brush.maskedBrush?.let {
        alphaChannelBytes(
            BrushTipMaskCache.tipMask(maskShape, GPU_MASK_REFERENCE_SIZE, GPU_MASK_REFERENCE_SIZE, it.hardness),
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
