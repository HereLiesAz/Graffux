package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.Dab
import com.hereliesaz.graffitixr.common.azphalt.DirtyRegion
import com.hereliesaz.graffitixr.common.util.SafeBitmap
import com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine

/**
 * An Impasto v1 stamp stroke on the GPU, in one engine session: the colour (the same stamping as
 * [GpuStampCommit]), then the paint height deposited under the stroke's dabs and the touched area
 * relief-shaded in place, exactly the CPU's order (DrawingEngine's v1 branch: paint, then
 * `ImpastoEngine.depositStroke`, then `ImpastoRegionShader.shade` over the dabs' region grown by
 * one pixel). The engine's shaders follow those two functions step for step (core/wgpu-engine
 * `impasto.wgsl`, checked against them by `tests/impasto.rs`).
 */
internal object GpuImpastoStroke {

    private const val TAG = "GpuImpastoStroke"

    /**
     * [stroke] painted onto a copy of [bitmap], with [heightMap] updated in place to the height after
     * it; null (and [heightMap] untouched) when the GPU or its impasto support is unavailable, so
     * the caller's CPU path starts from the pristine height.
     */
    @Suppress("LongParameterList")
    fun paintStroke(
        bitmap: Bitmap,
        stroke: StrokeCommand,
        brush: AzphaltBrush,
        mapped: List<Offset>,
        brushScale: Float,
        heightMap: FloatArray,
    ): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        val (movementDabs, heldDabs) = StrokeDabs.recorded(stroke, brush, mapped, brushScale, width, height)
            ?: (StrokeDabs.fromPath(stroke, brush, mapped, brushScale, width, height) to emptyList())
        val target = SafeBitmap.copy(bitmap)
        val workingHeight = heightMap.copyOf()
        val painted = target != null && render(
            target,
            StrokeDabs.batches(movementDabs, heldDabs, stroke.stampBatches),
            // The CPU deposits all movement dabs, then all held dabs.
            movementDabs + heldDabs,
            StampPaint.of(stroke, brush),
            workingHeight,
        )
        if (!painted) {
            target?.recycle()
            return null
        }
        workingHeight.copyInto(heightMap)
        return target
    }

    private fun render(
        target: Bitmap,
        batches: List<Pair<List<Dab>, List<Dab>>>,
        heightDabs: List<Dab>,
        paint: StampPaint,
        heights: FloatArray,
    ): Boolean {
        // Calibration's engines and the canvas's never coexist (GpuCalibration.stopForCanvas);
        // this runs on a commit worker, never on the touch path.
        GpuTuningController.stopCalibrationForCanvas()
        val engine = runCatching { GpuStampEngine() }.getOrNull()
        return engine != null && try {
            engine.init(target.width, target.height) &&
                engine.upload(target) &&
                GpuStampCommit.stampOnto(engine, batches, paint) &&
                engine.uploadImpastoHeight(heights) &&
                engine.depositImpastoHeight(
                    flatHeightDabs(heightDabs), paint.brush.hardness, paint.brush.impastoThicknessRate,
                ) &&
                shade(engine, heightDabs, target.width, target.height) &&
                engine.readback(target) &&
                engine.readImpastoHeight(heights)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            Log.w(TAG, "GPU impasto stroke failed; the CPU renderer takes this stroke", e)
            false
        } finally {
            engine.destroy()
        }
    }

    /** (x, y, radius, strength) per dab, strength being the CPU increment's per-dab factor. */
    private fun flatHeightDabs(dabs: List<Dab>): FloatArray {
        val out = FloatArray(dabs.size * HEIGHT_DAB_FLOATS)
        dabs.forEachIndexed { i, d ->
            val o = i * HEIGHT_DAB_FLOATS
            out[o] = d.x
            out[o + 1] = d.y
            out[o + 2] = d.radius
            out[o + 3] = d.alpha.coerceIn(0f, 1f) * d.flowMultiplier.coerceAtLeast(0f)
        }
        return out
    }

    /** The CPU's shade region: the dabs' bounds grown by one pixel (the normal's neighbours). */
    private fun shade(engine: GpuStampEngine, dabs: List<Dab>, width: Int, height: Int): Boolean {
        val touched = DirtyRegion.fromDabs(dabs) ?: return true
        val region = DirtyRegion(touched.left - 1, touched.top - 1, touched.right + 1, touched.bottom + 1)
            .clampTo(width, height)
        return region.isEmpty || engine.shadeImpastoRelief(
            region.left, region.top, region.width, region.height,
            IMPASTO_LIGHT_AZIMUTH_DEG, IMPASTO_LIGHT_ELEVATION_DEG, IMPASTO_LIGHT_STRENGTH,
        )
    }

    private const val HEIGHT_DAB_FLOATS = 4
}
