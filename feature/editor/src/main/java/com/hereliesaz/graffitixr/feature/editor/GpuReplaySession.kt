package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.util.Log
import com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine

/**
 * One GPU engine for a whole replay ([DrawingEngine.composite]: undo/redo rebuilds, stroke baking).
 * Consecutive GPU strokes paint onto the engine's layer back to back -- one upload before the first,
 * [GpuStampEngine.beginStroke] between them, one readback when a CPU step next needs the pixels
 * ([flush]) -- instead of a fresh engine, a full upload and a full readback per stroke.
 *
 * Between [paint] and [flush] the bitmap the strokes were painted onto ([mirror]) holds the pixels
 * from before them; the GPU holds the result. If the GPU fails in that window, [takeLost] hands the
 * caller those strokes to paint again on the CPU over [mirror]'s still-intact pixels.
 */
internal class GpuReplaySession : AutoCloseable {

    private var engine: GpuStampEngine? = null
    private var mirror: Bitmap? = null
    private val pending = ArrayList<StrokeCommand>()

    /** True once the GPU failed; the caller recovers with [takeLost] and stops using the session. */
    var broken = false
        private set

    /**
     * [painter] applied to [bitmap] on the session's engine. Returns [bitmap] itself: its pixels go
     * stale until [flush]. On failure the session breaks and [bitmap] is untouched.
     */
    fun paint(bitmap: Bitmap, stroke: StrokeCommand, painter: (GpuStampEngine) -> Boolean): Bitmap {
        val ok = !broken && try {
            val e = ready(bitmap)
            e != null && painter(e)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            Log.w(TAG, "GPU replay failed; the CPU repaints its strokes", t)
            false
        }
        if (ok) pending += stroke else broken = true
        return bitmap
    }

    /** Reads the GPU result into [mirror]. False (and broken) when the GPU failed. */
    fun flush(): Boolean {
        val m = mirror
        if (broken || m == null || pending.isEmpty()) return !broken
        val ok = engine?.readback(m) == true
        if (ok) pending.clear() else broken = true
        return ok
    }

    /** After a failure: the strokes painted since the last flush, which [mirror] doesn't hold. */
    fun takeLost(): List<StrokeCommand> = pending.toList().also {
        pending.clear()
        mirror = null
    }

    override fun close() {
        engine?.destroy()
        engine = null
    }

    /** The engine with [bitmap]'s current pixels on its layer and a fresh stroke begun. */
    private fun ready(bitmap: Bitmap): GpuStampEngine? {
        val e = engineFor(bitmap) ?: return null
        val continued = mirror === bitmap && (e.beginStroke() || flush() && e.upload(bitmap))
        val seeded = continued || flush() && e.upload(bitmap)
        if (seeded) mirror = bitmap
        return e.takeIf { seeded }
    }

    private fun engineFor(bitmap: Bitmap): GpuStampEngine? {
        val size = bitmap.width to bitmap.height
        val current = engine?.takeIf { mirrorSize == size }
        // A different size: land what the old engine holds first.
        return current ?: if (flush()) fresh(size) else null
    }

    private fun fresh(size: Pair<Int, Int>): GpuStampEngine? {
        engine?.destroy()
        mirror = null
        // Calibration's engines and the canvas's never coexist (GpuCalibration.stopForCanvas);
        // replays run on a worker, never on the touch path.
        GpuTuningController.stopCalibrationForCanvas()
        val created = runCatching { GpuStampEngine() }.getOrNull()
        engine = created?.takeIf { it.init(size.first, size.second) }
        if (engine == null) created?.destroy()
        mirrorSize = size
        return engine
    }

    private var mirrorSize: Pair<Int, Int>? = null

    private companion object {
        const val TAG = "GpuReplaySession"
    }
}
