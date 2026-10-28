package com.hereliesaz.graffitixr.common.azphalt.wgpu

/**
 * Photo adjustment layers on the GPU (core/wgpu-engine/src/adjust.rs): the composite below a layer
 * goes up once ([setSource]), its mask once ([setMask]), and each slider move is one compute pass
 * plus a readback ([render]). Pixels are premultiplied RGBA8, `width * height * 4` bytes, the
 * memory of an Android `ARGB_8888` bitmap. Parameters are a `PhotoAdjustParams` block.
 *
 * Its own wgpu device, apart from the stamp engine's. Calls are synchronized on the instance;
 * [create] returns null where the library or an adapter is missing (the caller then uses
 * `PhotoAdjustMath`, the CPU reference).
 */
class WgpuPhotoAdjuster private constructor(
    @Volatile private var handle: Long,
    val width: Int,
    val height: Int,
) : AutoCloseable {

    val isAlive: Boolean get() = handle != 0L

    @Synchronized fun setSource(rgbaPremultiplied: ByteArray): Boolean =
        handle != 0L && rgbaPremultiplied.size >= width * height * 4 &&
            WgpuNative.nativeAdjustSetSource(handle, rgbaPremultiplied)

    /** One byte per pixel, 255 = the full adjustment. */
    @Synchronized fun setMask(alpha: ByteArray): Boolean =
        handle != 0L && alpha.size >= width * height && WgpuNative.nativeAdjustSetMask(handle, alpha)

    @Synchronized fun setCurves(table: FloatArray): Boolean =
        handle != 0L && table.size >= CURVE_FLOATS && WgpuNative.nativeAdjustSetCurves(handle, table)

    @Synchronized fun render(params: FloatArray, out: ByteArray): Boolean =
        handle != 0L && params.size >= PARAM_FLOATS && out.size >= width * height * 4 &&
            WgpuNative.nativeAdjustRender(handle, params, out)

    /** How long the last [render] took, GPU passes and readback, in nanoseconds. */
    @get:Synchronized
    val lastRenderNanos: Long get() = if (handle == 0L) 0L else WgpuNative.nativeAdjustLastNanos(handle)

    @Synchronized
    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) WgpuNative.nativeAdjustDestroy(h)
    }

    companion object {
        const val PARAM_FLOATS = 56
        const val CURVE_FLOATS = 1024

        /** Null when the library is missing or there is no adapter with compute shaders. */
        fun create(
            width: Int,
            height: Int,
            backend: WgpuStampEngine.Backend = WgpuStampEngine.Backend.AUTO,
        ): WgpuPhotoAdjuster? {
            if (width <= 0 || height <= 0 || !WgpuLibrary.load()) return null
            val handle = try {
                WgpuNative.nativeAdjustCreate(width, height, backend.id)
            } catch (e: UnsatisfiedLinkError) {
                0L
            }
            return if (handle == 0L) null else WgpuPhotoAdjuster(handle, width, height)
        }
    }
}
