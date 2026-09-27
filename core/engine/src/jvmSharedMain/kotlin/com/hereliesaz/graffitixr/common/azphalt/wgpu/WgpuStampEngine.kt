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

    /** An R8 image (tip mask, grain tile, secondary mask). */
    class AlphaImage(val pixels: ByteArray, val width: Int, val height: Int)

    val isAlive: Boolean get() = handle != 0L

    /** "Vulkan: <adapter> (<driver>)", or "" once closed. */
    @get:Synchronized
    val adapterDescription: String get() = if (handle == 0L) "" else WgpuNative.nativeAdapterDescription(handle)

    @Synchronized fun clear(): Boolean = handle != 0L && WgpuNative.nativeClear(handle)

    @Synchronized fun upload(rgbaPremultiplied: ByteArray): Boolean =
        handle != 0L && WgpuNative.nativeUpload(handle, rgbaPremultiplied)

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

    /** `colorSmudge`. [dabs] holds [SMUDGE_FLOATS] floats per dab, at least two dabs. */
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
    ): Boolean = handle != 0L && WgpuNative.nativeColorSmudge(
        handle, dabs, mode, radiusPx, feathering, smearAlpha, paintColorArgb, dilution,
        sampleSourceRgba, if (sampleSourceRgba != null) width else 0, if (sampleSourceRgba != null) height else 0,
    )

    /** Copies the rectangle dirtied since the last readback into [out] (`width * height * 4` bytes). */
    @Synchronized fun readback(out: ByteArray): Boolean = handle != 0L && WgpuNative.nativeReadback(handle, out)

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
