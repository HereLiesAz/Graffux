// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/VulkanStampEngine.kt
package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.util.NativeLibLoader

/**
 * Kotlin bridge to the native Vulkan compute dab compositor (Phase 3 of
 * `docs/Native Rendering Engine Design.md` §9) — VulkanStampEngine.cpp/.h and stamp.comp,
 * bridged via the `nativeVulkan*` JNI exports appended to GraffitiJNI.cpp.
 *
 * A [VulkanStampEngine] instance owns exactly one GPU-side layer image, created by [init] and
 * released by [destroy]. Not thread-safe on the Kotlin side either — matches the native class's
 * own contract; callers own serializing their use of one instance (e.g. from a single drawing
 * coroutine/thread), the same way [SlamManager] callers already serialize AR-session calls.
 *
 * [init] can legitimately fail — no compute-capable Vulkan device, a storage-image format the
 * driver rejects, or (this being new, unshipped code) a device/driver combination this hasn't
 * been validated against yet. Callers MUST treat a `false` return as "fall back to the CPU
 * (ImageProcessor/StampBrushRenderer) path for this stroke", not as fatal — this class provides
 * no CPU fallback itself.
 */
class VulkanStampEngine {

    init {
        NativeLibLoader.loadAll()
    }

    private var initialized = false

    /**
     * Creates the layer image at [width]x[height] and the compute pipeline. Returns false (engine
     * left unusable — call [destroy] before retrying) if the device has no usable Vulkan compute
     * queue or resource creation otherwise fails.
     */
    fun init(width: Int, height: Int): Boolean {
        initialized = nativeInit(width, height)
        return initialized
    }

    val isInitialized: Boolean get() = initialized

    /**
     * Seeds the layer image with [bitmap]'s current pixels, replacing whatever it currently holds
     * — [stampDabs] never clears the layer, so a fresh [init] starts transparent, and a live
     * painting session needs the document's existing pixels underneath the new dabs. [bitmap] MUST
     * be `ARGB_8888`, non-hardware, and exactly the width/height passed to [init] — the same
     * requirement [readback] has, since this engine assumes its layer image and the bitmap it
     * round-trips through always agree on dimensions.
     */
    fun upload(bitmap: Bitmap): Boolean {
        if (!initialized) return false
        require(bitmap.config == Bitmap.Config.ARGB_8888) {
            "VulkanStampEngine.upload requires an ARGB_8888 bitmap, got ${bitmap.config}"
        }
        return nativeUpload(bitmap)
    }

    /**
     * Stamps [dabs] onto the layer image, in submission order, using [colorArgb] (standard
     * Android ARGB int — its own alpha channel combines multiplicatively with each dab's own
     * [BrushDab.alpha], matching `StampBrushRenderer.paintDabs`'s `baseAlpha * d.alpha`) and
     * [hardness] (`brush.hardness`, 0..1). Returns false if the engine isn't initialized or
     * [dabs] is empty; the call is otherwise synchronous (blocks until the GPU dispatch
     * completes) so a subsequent [readback] always sees this call's result.
     */
    fun stampDabs(dabs: List<BrushDab>, colorArgb: Int, hardness: Float): Boolean {
        if (!initialized || dabs.isEmpty()) return false
        val flat = FloatArray(dabs.size * 5)
        for (i in dabs.indices) {
            val d = dabs[i]
            val base = i * 5
            flat[base] = d.x
            flat[base + 1] = d.y
            flat[base + 2] = d.radius
            flat[base + 3] = d.alpha
            flat[base + 4] = d.angleDeg
        }
        return nativeStampDabs(flat, colorArgb, hardness)
    }

    /**
     * Reads the current layer contents into [bitmap], which MUST be `ARGB_8888`, non-hardware,
     * and exactly the width/height passed to [init]. Returns false (leaving [bitmap] untouched)
     * if the engine isn't initialized or the bitmap doesn't meet those requirements.
     */
    fun readback(bitmap: Bitmap): Boolean {
        if (!initialized) return false
        require(bitmap.config == Bitmap.Config.ARGB_8888) {
            "VulkanStampEngine.readback requires an ARGB_8888 bitmap, got ${bitmap.config}"
        }
        return nativeReadback(bitmap)
    }

    /** Releases the GPU layer image and pipeline. Safe to call even if [init] was never called or failed. */
    fun destroy() {
        nativeDestroy()
        initialized = false
    }

    private external fun nativeInit(width: Int, height: Int): Boolean
    private external fun nativeUpload(inBitmap: Bitmap): Boolean
    private external fun nativeStampDabs(dabData: FloatArray, colorArgb: Int, hardness: Float): Boolean
    private external fun nativeReadback(outBitmap: Bitmap): Boolean
    private external fun nativeDestroy()
}

/**
 * One dab, mirroring `BrushStamps.Dab` (`core/common/.../azphalt/BrushStamps.kt`) — kept as a
 * separate type here rather than depending on `core:common`'s `azphalt` package directly, since
 * `core:nativebridge` otherwise has no dependency on that module; callers on the app side map
 * `BrushStamps.Dab` to this 1:1.
 */
data class BrushDab(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val angleDeg: Float,
)
