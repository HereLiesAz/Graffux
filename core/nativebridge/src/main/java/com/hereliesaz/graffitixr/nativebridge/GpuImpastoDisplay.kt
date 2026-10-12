package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap

/**
 * A live impasto stroke's lit presentation on a wgpu [engine] (core/wgpu-engine impasto.rs): the
 * relief shading goes into a display buffer, the layer staying the raw paint, so the same region
 * can be shaded again as the stroke deposits more height. False when unsupported.
 */
class GpuImpastoDisplay(private val engine: GpuStampEngine) {

    private inline fun call(crossinline block: (Long) -> Boolean): Boolean = synchronized(engine) {
        val handle = engine.handleForDocument
        handle != 0L && GpuRenderThread.call { block(handle) }
    }

    /** ImpastoRegionShader.shade of the engine's height over [rect] ({x, y, w, h}) into the display. */
    fun shade(rect: IntArray, azimuthDeg: Float, elevationDeg: Float, strength: Float): Boolean =
        call { handle ->
            val (x, y, w) = rect
            nativeShade(handle, x, y, w, rect.last(), azimuthDeg, elevationDeg, strength)
        }

    /** Reads [rect] of the display into the same rectangle of [bitmap] (a full-layer bitmap). */
    fun read(bitmap: Bitmap, rect: IntArray): Boolean = call { nativeRead(it, bitmap, rect) }

    private companion object {
        @JvmStatic
        @Suppress("LongParameterList")
        private external fun nativeShade(
            handle: Long, x: Int, y: Int, w: Int, h: Int, azimuthDeg: Float, elevationDeg: Float, strength: Float,
        ): Boolean

        @JvmStatic
        private external fun nativeRead(handle: Long, bitmap: Bitmap, rect: IntArray): Boolean
    }
}
