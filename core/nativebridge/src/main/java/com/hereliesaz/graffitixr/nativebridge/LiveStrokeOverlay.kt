// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/LiveStrokeOverlay.kt
package com.hereliesaz.graffitixr.nativebridge

import android.hardware.HardwareBuffer
import android.view.Surface
import com.hereliesaz.graffitixr.common.util.NativeLibLoader

/**
 * Direct display of the live stroke (LiveStrokeOverlay.cpp): a SurfaceControl layer over the canvas
 * that Vulkan writes into, front-buffered where the device supports it, so new paint skips the
 * Compose frame entirely. Holds only the stroke's own contribution (see live_overlay.comp), so the
 * canvas underneath keeps showing the pre-stroke layer until the stroke commits.
 *
 * Long-lived (one per editor surface), independent of the per-stroke stamp engines: each stroke
 * hands it the engine's hardware-buffer-backed layer in [beginStroke]. Every call is serialized on
 * this instance; the live-render worker and the UI thread both use it.
 */
@Suppress("TooManyFunctions") // One Kotlin entry point plus its JNI declaration per native call.
class LiveStrokeOverlay private constructor(private var handle: Long) : AutoCloseable {

    /** Parents the overlay to [surface] (a SurfaceView's), [width]x[height] pixels. Idempotent. */
    @Synchronized
    fun attach(surface: Surface, width: Int, height: Int): Boolean =
        handle != 0L && nativeAttach(handle, surface, width, height)

    @Synchronized
    fun detach() {
        if (handle != 0L) nativeDetach(handle)
    }

    /** Stroke start: [layer] is the stamp engine's layer, already seeded with the pre-stroke pixels. */
    @Synchronized
    fun beginStroke(layer: HardwareBuffer, width: Int, height: Int): Boolean =
        handle != 0L && nativeBeginStroke(handle, layer, width, height)

    /**
     * Redraws overlay pixels [x], [y], [w], [h]. [overlayToLayer] maps an overlay pixel to layer
     * pixels: `(m[0]*x + m[1]*y + m[2], m[3]*x + m[4]*y + m[5])`.
     */
    @Synchronized
    fun present(overlayToLayer: FloatArray, x: Int, y: Int, w: Int, h: Int): Boolean =
        handle != 0L && nativePresent(handle, overlayToLayer, intArrayOf(x, y, w, h))

    /** Clears and hides the overlay; call once the committed stroke is on screen. */
    @Synchronized
    fun endStroke(): Boolean = handle != 0L && nativeEndStroke(handle)

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    private external fun nativeAttach(handle: Long, surface: Surface, width: Int, height: Int): Boolean
    private external fun nativeDetach(handle: Long)
    private external fun nativeBeginStroke(handle: Long, layer: HardwareBuffer, width: Int, height: Int): Boolean
    private external fun nativePresent(handle: Long, matrix: FloatArray, region: IntArray): Boolean
    private external fun nativeEndStroke(handle: Long): Boolean
    private external fun nativeDestroy(handle: Long)

    companion object {
        /** SharedPreferences key (in [GpuStampEngine.Backend.PREFS]) for the Settings switch. */
        const val ENABLED_KEY = "direct_display"

        /** Whether new strokes use the overlay. Set from Settings at startup and on change. */
        @Volatile
        var enabled: Boolean = false

        /** Null when the device can't: API < 29, no Vulkan AHardwareBuffer import, no native lib. */
        fun create(): LiveStrokeOverlay? = try {
            NativeLibLoader.loadAll()
            nativeCreateHandle().takeIf { it != 0L }?.let(::LiveStrokeOverlay)
        } catch (_: Throwable) {
            null
        }

        @JvmStatic
        private external fun nativeCreate(): Long

        private fun nativeCreateHandle(): Long = nativeCreate()
    }
}
