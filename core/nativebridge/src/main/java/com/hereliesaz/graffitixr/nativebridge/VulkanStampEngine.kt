// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/VulkanStampEngine.kt
package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
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

    // The native graffux::VulkanStampEngine*, opaque to Kotlin, 0 when none exists. Each Kotlin
    // instance owns exactly one — nativeInit allocates a fresh native object and hands back its
    // address rather than reaching into a shared global, so two VulkanStampEngine instances never
    // fight over one native engine (a prior version did exactly that: initializing a second
    // instance destroyed the first's layer, and destroying either invalidated the other while its
    // `initialized` flag stayed true).
    private var nativeHandle: Long = 0L

    /**
     * Creates the layer image at [width]x[height] and the compute pipeline. Returns false (engine
     * left unusable — call [destroy] before retrying) if the device has no usable Vulkan compute
     * queue or resource creation otherwise fails.
     */
    fun init(width: Int, height: Int): Boolean {
        if (nativeHandle != 0L) destroy()  // guard against leaking a live handle on re-init.
        nativeHandle = nativeInit(width, height)
        return nativeHandle != 0L
    }

    val isInitialized: Boolean get() = nativeHandle != 0L

    /**
     * Alternative to [init]: the layer's memory is a freshly-allocated `AHardwareBuffer` imported
     * into Vulkan (docs/Native Rendering Engine Design.md §2's zero-copy interop) instead of
     * engine-private device memory. On success, [hardwareBuffer] returns that same memory — a
     * [stampDabs] write is visible through it directly, no [readback] copy needed to display it
     * (e.g. via `Bitmap.wrapHardwareBuffer`, API 29+). [upload]/[readback] still work afterward too,
     * for the cases (seeding a session with a document's existing pixels; a hardware bitmap can't
     * itself be drawn into directly) that still need a CPU-visible round trip.
     *
     * Returns false — the caller should fall back to [init] — if the device/driver combination
     * doesn't support AHardwareBuffer import for this format/usage, same failure modes [init] has.
     */
    fun initHardwareBufferBacked(width: Int, height: Int): Boolean {
        if (nativeHandle != 0L) destroy()
        nativeHandle = nativeInitHardwareBuffer(width, height)
        return nativeHandle != 0L
    }

    /**
     * The `AHardwareBuffer` backing the layer when [initHardwareBufferBacked] was used, wrapped as
     * a Java `HardwareBuffer` — or null after plain [init], or if the engine isn't initialized.
     * Each call returns a distinct `HardwareBuffer` object holding its own reference (matching
     * `AHardwareBuffer_toHardwareBuffer`'s contract): it stays valid independent of this engine's
     * lifetime once obtained, and the caller is responsible for eventually calling `close()` on it.
     */
    fun getHardwareBuffer(): HardwareBuffer? {
        if (!isInitialized) return null
        return nativeGetHardwareBuffer(nativeHandle)
    }

    /**
     * Seeds the layer image with [bitmap]'s current pixels, replacing whatever it currently holds
     * — [stampDabs] never clears the layer, so a fresh [init] starts transparent, and a live
     * painting session needs the document's existing pixels underneath the new dabs. [bitmap] MUST
     * be `ARGB_8888`, non-hardware, and exactly the width/height passed to [init] — the same
     * requirement [readback] has, since this engine assumes its layer image and the bitmap it
     * round-trips through always agree on dimensions.
     */
    fun upload(bitmap: Bitmap): Boolean {
        if (!isInitialized) return false
        require(bitmap.config == Bitmap.Config.ARGB_8888) {
            "VulkanStampEngine.upload requires an ARGB_8888 bitmap, got ${bitmap.config}"
        }
        return nativeUpload(nativeHandle, bitmap)
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
        if (!isInitialized || dabs.isEmpty()) return false
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
        return nativeStampDabs(nativeHandle, flat, colorArgb, hardness)
    }

    /**
     * Reads the current layer contents into [bitmap], which MUST be `ARGB_8888`, non-hardware,
     * and exactly the width/height passed to [init]. Returns false (leaving [bitmap] untouched)
     * if the engine isn't initialized or the bitmap doesn't meet those requirements.
     */
    fun readback(bitmap: Bitmap): Boolean {
        if (!isInitialized) return false
        require(bitmap.config == Bitmap.Config.ARGB_8888) {
            "VulkanStampEngine.readback requires an ARGB_8888 bitmap, got ${bitmap.config}"
        }
        return nativeReadback(nativeHandle, bitmap)
    }

    /** Releases the GPU layer image and pipeline. Safe to call even if [init] was never called or failed. */
    fun destroy() {
        if (nativeHandle != 0L) nativeDestroy(nativeHandle)
        nativeHandle = 0L
    }

    private external fun nativeInit(width: Int, height: Int): Long
    private external fun nativeInitHardwareBuffer(width: Int, height: Int): Long
    private external fun nativeGetHardwareBuffer(handle: Long): HardwareBuffer?
    private external fun nativeUpload(handle: Long, inBitmap: Bitmap): Boolean
    private external fun nativeStampDabs(handle: Long, dabData: FloatArray, colorArgb: Int, hardness: Float): Boolean
    private external fun nativeReadback(handle: Long, outBitmap: Bitmap): Boolean
    private external fun nativeDestroy(handle: Long)
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
