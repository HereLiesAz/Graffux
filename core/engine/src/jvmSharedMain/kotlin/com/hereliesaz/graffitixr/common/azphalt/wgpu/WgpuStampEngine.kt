package com.hereliesaz.graffitixr.common.azphalt.wgpu

/**
 * The wgpu GPU stamp engine (core/wgpu-engine) -- the long-term single brush engine, the same
 * Kotlin class on Android and desktop. Mirrors the C++ `StampEngine` contract (StampEngine.h):
 *
 * * the layer is premultiplied RGBA8, row-major, `width * height * 4` bytes -- byte-identical to an
 *   Android `ARGB_8888` bitmap's memory;
 * * dabs are packed as [DAB_FLOATS] floats each (`GpuDab`), secondary dabs as [SECONDARY_FLOATS],
 *   Color Smudge dabs as [SMUDGE_FLOATS] -- see [WgpuDabs] for building them;
 * * [readback] writes only the rectangle changed since the previous readback, so keep one buffer
 *   per layer and it stays current.
 *
 * Not thread-safe on the native side; every call here is synchronized on the instance. [create]
 * returns null when the library cannot be loaded or no adapter with compute support exists --
 * callers keep their CPU path for that case. On Android the app normally reaches this engine
 * through `GpuStampEngine` (Settings -> GPU engine -> wgpu) instead; this class is what the
 * desktop app and JVM tests use.
 */
@Suppress("TooManyFunctions")
class WgpuStampEngine private constructor(
    private var handle: Long,
    val width: Int,
    val height: Int,
) : AutoCloseable {

    /** Adapter choice. [AUTO] prefers Vulkan/Metal/DX12 and falls back to GL; `WGPU_BACKEND` overrides it. */
    enum class Backend(val id: Int) { AUTO(0), VULKAN(1), GL(2) }

    /** `SubstrateStampParams`. */
    data class Substrate(
        val enabled: Boolean = false,
        val hasPaintHeight: Boolean = false,
        val baseHeight: Float = 0f,
        val heightScale: Float = 0f,
        val textureScale: Float = 1f,
        val textureOffsetX: Float = 0f,
        val textureOffsetY: Float = 0f,
    ) {
        internal fun toArray() = floatArrayOf(
            if (enabled) 1f else 0f, if (hasPaintHeight) 1f else 0f,
            baseHeight, heightScale, textureScale, textureOffsetX, textureOffsetY,
        )
    }

    /** A pixel rectangle, [x]/[y] inclusive; empty when either extent is zero. */
    data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val isEmpty: Boolean get() = width <= 0 || height <= 0

        companion object {
            val EMPTY = PixelRect(0, 0, 0, 0)
        }
    }

    /** An R8 image (tip mask, grain tile, secondary mask). */
    class AlphaImage(val pixels: ByteArray, val width: Int, val height: Int)

    val isAlive: Boolean get() = handle != 0L

    /** "Vulkan: <adapter> (<driver>)", or "" once closed. */
    @get:Synchronized
    val adapterDescription: String get() = if (handle == 0L) "" else WgpuNative.nativeAdapterDescription(handle)

    @Synchronized fun clear(): Boolean = handle != 0L && WgpuNative.nativeClear(handle)

    @Synchronized fun upload(rgbaPremultiplied: ByteArray): Boolean =
        handle != 0L && WgpuNative.nativeUpload(handle, rgbaPremultiplied)

    /**
     * Restores rows `y until y + rows` from [rgbaPremultiplied] (a full layer image) without starting
     * a new stroke -- for re-rendering a stroke over its base frame by frame. Not in StampEngine.h.
     */
    @Synchronized fun uploadRows(rgbaPremultiplied: ByteArray, y: Int, rows: Int): Boolean =
        handle != 0L && WgpuNative.nativeUploadRows(handle, rgbaPremultiplied, y, rows)

    @Synchronized fun uploadSubstrateHeight(heightR8: ByteArray, width: Int, height: Int): Boolean =
        handle != 0L && WgpuNative.nativeUploadSubstrateHeight(handle, heightR8, width, height)

    @Synchronized fun uploadPaintHeight(heights: FloatArray, width: Int, height: Int): Boolean =
        handle != 0L && WgpuNative.nativeUploadPaintHeight(handle, heights, width, height)

    /** `stampDabs`. [dabs] holds [DAB_FLOATS] floats per dab. */
    @Suppress("LongParameterList")
    @Synchronized
    fun stampDabs(
        dabs: FloatArray,
        colorArgb: Int,
        hardness: Float,
        buildUp: Boolean = false,
        substrate: Substrate? = null,
        strokeMax: Boolean = false,
    ): Boolean = handle != 0L && dabs.size >= DAB_FLOATS &&
        WgpuNative.nativeStampDabs(handle, dabs, colorArgb, hardness, buildUp, substrate?.toArray(), strokeMax)

    /** `stampMaskedDabs`. [secondaryDabs], when given, holds one [SECONDARY_FLOATS] record per dab. */
    @Suppress("LongParameterList")
    @Synchronized
    fun stampMaskedDabs(
        dabs: FloatArray,
        colorArgb: Int,
        mask: AlphaImage,
        grain: AlphaImage? = null,
        grainCanvasLocked: Boolean = false,
        grainScale: Float = 1f,
        grainPhaseX: Float = 0f,
        grainPhaseY: Float = 0f,
        secondaryDabs: FloatArray? = null,
        secondaryMask: AlphaImage? = null,
        substrate: Substrate? = null,
    ): Boolean = handle != 0L && dabs.size >= DAB_FLOATS && WgpuNative.nativeStampMaskedDabs(
        handle, dabs, colorArgb, 0f, mask.pixels, mask.width, mask.height,
        grain?.pixels, grain?.width ?: 0, grain?.height ?: 0, grainCanvasLocked, grainScale, grainPhaseX, grainPhaseY,
        secondaryDabs, secondaryMask?.pixels, secondaryMask?.width ?: 0, secondaryMask?.height ?: 0,
        substrate?.toArray(),
    )

    /**
     * `colorSmudge`. [dabs] holds [SMUDGE_FLOATS] floats per dab (build it with
     * [WgpuDabs.colorSmudge]), at least two dabs; anything else returns false without a native call.
     */
    @Suppress("LongParameterList")
    @Synchronized
    fun colorSmudge(
        dabs: FloatArray,
        mode: Int,
        radiusPx: Float,
        feathering: Float,
        smearAlpha: Boolean,
        paintColorArgb: Int,
        dilution: Float = 0f,
        sampleSourceRgba: ByteArray? = null,
    ): Boolean = handle != 0L && dabs.size >= 2 * SMUDGE_FLOATS && dabs.size % SMUDGE_FLOATS == 0 &&
        WgpuNative.nativeColorSmudge(
        handle, dabs, mode, radiusPx, feathering, smearAlpha, paintColorArgb, dilution,
        sampleSourceRgba, if (sampleSourceRgba != null) width else 0, if (sampleSourceRgba != null) height else 0,
    )

    /** Copies the rectangle dirtied since the last readback into [out] (`width * height * 4` bytes). */
    @Synchronized fun readback(out: ByteArray): Boolean = handle != 0L && WgpuNative.nativeReadback(handle, out)

    /**
     * [readback] that returns the rectangle it copied (only that rectangle crosses from the GPU), an
     * empty [PixelRect] when nothing was dirty, or null on failure.
     */
    @Synchronized
    fun readbackRect(out: ByteArray): PixelRect? {
        if (handle == 0L) return null
        val rect = IntArray(RECT_INTS)
        return if (WgpuNative.nativeReadbackRect(handle, out, rect)) PixelRect(rect[0], rect[1], rect[2], rect[3]) else null
    }

    /** Reads [rect] of the layer into [out], tightly packed (`rect.width * 4` bytes per row). */
    @Synchronized
    fun readRegion(rect: PixelRect, out: ByteArray): Boolean =
        handle != 0L && WgpuNative.nativeReadRegion(handle, rect.x, rect.y, rect.width, rect.height, out)

    // ---- Resident layers (core/wgpu-engine/src/resident.rs) ----------------------------------
    // A layer stays on the GPU across strokes, keyed by [layerKey] and tagged with a content
    // generation the caller keeps unique across layers. bindLayer/uploadLayer start a stroke on it
    // and return a bind session (0 = miss / failure); commitLayer (the GPU result is the committed
    // layer) or refreshLayer (the CPU committed; re-upload the stroke's rows plus [PixelRect]) retag
    // it afterwards. Anything else that changes the CPU layer must invalidateLayer it.

    /** Session (> 0) when [layerKey] is resident at [generation]; 0 = upload with [uploadLayer]. */
    @Synchronized fun bindLayer(layerKey: Long, generation: Long): Long =
        if (handle == 0L) 0L else WgpuNative.nativeBindLayer(handle, layerKey, generation)

    /** Uploads [rgbaPremultiplied] as [layerKey] at [generation]; the caller's readback buffer must already hold it. */
    @Synchronized fun uploadLayer(layerKey: Long, generation: Long, rgbaPremultiplied: ByteArray): Long =
        if (handle == 0L) 0L else WgpuNative.nativeUploadLayer(handle, layerKey, generation, rgbaPremultiplied)

    @Synchronized fun commitLayer(layerKey: Long, session: Long, generation: Long): Boolean =
        handle != 0L && WgpuNative.nativeCommitLayer(handle, layerKey, session, generation)

    @Synchronized
    fun refreshLayer(layerKey: Long, session: Long, generation: Long, rgbaPremultiplied: ByteArray, changed: PixelRect): Boolean =
        handle != 0L && WgpuNative.nativeRefreshLayer(
            handle, layerKey, session, generation, rgbaPremultiplied,
            changed.x, changed.y, changed.width, changed.height,
        )

    @Synchronized fun invalidateLayer(layerKey: Long): Boolean =
        handle != 0L && WgpuNative.nativeInvalidateLayer(handle, layerKey)

    @Synchronized fun invalidateAllLayers() {
        if (handle != 0L) WgpuNative.nativeInvalidateAllLayers(handle)
    }

    @Synchronized fun setResidentBudget(bytes: Long) {
        if (handle != 0L) WgpuNative.nativeSetResidentBudget(handle, bytes)
    }

    /** (resident layer count, bytes they hold). */
    @get:Synchronized
    val residentStats: Pair<Long, Long> get() {
        val stats = if (handle == 0L) null else WgpuNative.nativeResidentStats(handle)
        return if (stats == null || stats.size < 2) 0L to 0L else stats[0] to stats[1]
    }

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) WgpuNative.nativeDestroy(h)
    }

    companion object {
        const val DAB_FLOATS = 16
        const val SECONDARY_FLOATS = 8
        const val SMUDGE_FLOATS = 11
        private const val RECT_INTS = 4

        /** Null when the library is missing or there is no usable GPU adapter. */
        fun create(width: Int, height: Int, backend: Backend = Backend.AUTO): WgpuStampEngine? {
            if (width <= 0 || height <= 0 || !WgpuLibrary.load()) return null
            val handle = try {
                WgpuNative.nativeCreate(width, height, backend.id)
            } catch (e: UnsatisfiedLinkError) {
                0L
            }
            return if (handle == 0L) null else WgpuStampEngine(handle, width, height)
        }
    }
}
