package com.hereliesaz.graffitixr.nativebridge

/**
 * The retouch tools that read their neighbourhood -- Blur, Sharpen, Clone -- on a wgpu [engine]
 * (core/wgpu-engine retouch.rs). The stroke takes its shadow first (GpuSelection.takeShadow); [run]
 * then writes the tool's result over the stroke's dabs from that shadow, so running it again from
 * the same shadow gives the same pixels. False when the engine is not initialized or the library
 * lacks it.
 */
class GpuRetouch(private val engine: GpuStampEngine) {

    /**
     * [kind] ([BLUR], [SHARPEN], [CLONE]) over [dabs] (x, y, radius, hardness per dab): Blur's
     * downscale [factor], Sharpen's [amount], Clone's source offset ([offsetX], [offsetY]) and
     * [wrap].
     */
    @Suppress("LongParameterList")
    fun run(
        kind: Int,
        dabs: FloatArray,
        factor: Int = 2,
        amount: Float = 0f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        wrap: Boolean = false,
        wrapFrameLeft: Int = 0,
        wrapFrameTop: Int = 0,
        wrapFrameWidth: Int = 0,
        wrapFrameHeight: Int = 0,
    ): Boolean = synchronized(engine) {
        val handle = engine.handleForDocument
        val params = floatArrayOf(
            factor.toFloat(), amount, offsetX, offsetY, if (wrap) 1f else 0f,
            wrapFrameLeft.toFloat(), wrapFrameTop.toFloat(),
            wrapFrameWidth.toFloat(), wrapFrameHeight.toFloat(),
        )
        handle != 0L && GpuRenderThread.call { nativeRetouch(handle, kind, params, dabs) }
    }

    companion object {
        const val BLUR = 0
        const val SHARPEN = 1
        const val CLONE = 2

        /** Floats per dab in [run]'s dabs. */
        const val DAB_FLOATS = 4

        @JvmStatic
        private external fun nativeRetouch(handle: Long, kind: Int, params: FloatArray, dabs: FloatArray): Boolean
    }
}
