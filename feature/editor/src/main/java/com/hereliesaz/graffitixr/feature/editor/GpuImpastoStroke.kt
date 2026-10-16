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
 * relief-shaded in place: paint, then the height deposit (`ImpastoEngine.depositStroke`'s math),
 * then relief shading (`ImpastoRegionShader.shade`'s) over the dabs' region grown by one pixel. The
 * engine's shaders follow those two functions step for step (core/wgpu-engine `impasto.wgsl`,
 * checked against them by `tests/impasto.rs`). Under a selection the height deposit is gated by
 * its coverage, as Impasto v2 gates height and wetness; the caller clips the colour.
 */
internal object GpuImpastoStroke {

    private const val TAG = "GpuImpastoStroke"

    /**
     * [stroke] painted onto a copy of [bitmap], with [heightMap] updated in place to the height after
     * it; null (and [heightMap] untouched) when the GPU or its impasto support is unavailable.
     * [coverage] (one value per pixel, 0..1; null = everywhere) scales how much of the new height
     * lands at each pixel, so a selection keeps relief out of pixels the stroke may not paint.
     */
    @Suppress("LongParameterList")
    fun paintStroke(
        bitmap: Bitmap,
        stroke: StrokeCommand,
        brush: AzphaltBrush,
        mapped: List<Offset>,
        brushScale: Float,
        heightMap: FloatArray,
        coverage: FloatArray? = null,
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
            // Height deposits all movement dabs, then all held dabs.
            movementDabs + heldDabs,
            StampPaint.of(stroke, brush),
            workingHeight,
        )
        if (!painted) {
            target?.recycle()
            return null
        }
        if (coverage != null && coverage.size == heightMap.size) {
            for (i in heightMap.indices) {
                heightMap[i] += (workingHeight[i] - heightMap[i]) * coverage[i].coerceIn(0f, 1f)
            }
        } else {
            workingHeight.copyInto(heightMap)
        }
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
            Log.w(TAG, "GPU impasto stroke failed; the stroke is not painted", e)
            false
        } finally {
            engine.destroy()
        }
    }

    /** (x, y, radius, strength) per dab, strength being `ImpastoEngine`'s per-dab increment factor. */
    fun flatHeightDabs(dabs: List<Dab>): FloatArray {
        val out = FloatArray(dabs.size * HEIGHT_DAB_FLOATS)
        dabs.forEachIndexed { i, d ->
            val o = i * HEIGHT_DAB_FLOATS
            out[o + X] = d.x
            out[o + Y] = d.y
            out[o + RADIUS] = d.radius
            out[o + STRENGTH] = d.alpha.coerceIn(0f, 1f) * d.flowMultiplier.coerceAtLeast(0f)
        }
        return out
    }

    /** The shade region: the dabs' bounds grown by one pixel (the normal's neighbours). */
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
    private const val X = 0
    private const val Y = 1
    private const val RADIUS = 2
    private const val STRENGTH = 3
}
