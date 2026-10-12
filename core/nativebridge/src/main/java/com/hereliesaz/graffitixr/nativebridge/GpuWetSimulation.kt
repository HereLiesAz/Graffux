package com.hereliesaz.graffitixr.nativebridge

/**
 * Impasto v2's wet material simulation on a wgpu [engine] (core/wgpu-engine wet.rs,
 * graffux_wgpu `gfx_wgpu_wet_*`). Every call runs on [GpuRenderThread] and returns false when the
 * engine is not initialized or the library lacks the simulation (the caller then takes the CPU path).
 */
class GpuWetSimulation(private val engine: GpuStampEngine) {

    private inline fun call(crossinline block: (Long) -> Boolean): Boolean = synchronized(engine) {
        val handle = engine.handleForDocument
        handle != 0L && GpuRenderThread.call { block(handle) }
    }

    /**
     * Hands the simulation the layer's canonical state: straight ARGB [raw] pigment, [height] and
     * [wetness] (one value per layer pixel), each [tileSize] tile's [active] flag (row-major, nonzero
     * = active) and the [media] table ([MEDIA_FLOATS] per tile).
     */
    @Suppress("LongParameterList")
    fun begin(
        raw: IntArray,
        height: FloatArray,
        wetness: FloatArray,
        active: IntArray,
        tileSize: Int,
        media: FloatArray,
    ): Boolean = call { nativeWetBegin(it, raw, height, wetness, active, tileSize, media) }

    /** Replaces one input: [MEDIA], [COVERAGE] (null = no selection) or [DABS]. */
    fun buffer(which: Int, data: FloatArray?): Boolean = call { nativeWetBuffer(it, which, data) }

    /** Runs one simulation step ([RAW_TO_LAYER] .. [SHADE]) with its float arguments. */
    fun op(op: Int, vararg args: Float): Boolean = call { nativeWetOp(it, op, args) }

    /** Reads the state back into [raw], [height] and [wetness] (one value per layer pixel). */
    fun read(raw: IntArray, height: FloatArray, wetness: FloatArray): Boolean =
        call { nativeWetRead(it, raw, height, wetness) }

    /**
     * The control words into [out]: each tile's active flag, as many zero words, then one flag word
     * per dab ([FLAG_HEIGHT], [FLAG_WETNESS]).
     */
    fun readControl(out: IntArray): Boolean = call { nativeWetReadFlags(it, out) }

    companion object {
        // Buffers for [buffer], steps for [op] (argument lists in the Rust docs), dab flags.
        const val MEDIA = 0
        const val COVERAGE = 1
        const val DABS = 2
        const val RAW_TO_LAYER = 0
        const val LAYER_TO_RAW = 1
        const val SAVE_RAW = 2
        const val BLEND_SELECTION = 3
        const val TRANSFER = 4
        const val DEPOSIT = 5
        const val PIGMENT = 6
        const val ADVANCE = 7
        const val LEVEL = 8
        const val SHADE = 9
        const val FLAG_HEIGHT = 1
        const val FLAG_WETNESS = 2

        /** Floats per tile in the media table; [DAB_FLOATS] per dab. */
        const val MEDIA_FLOATS = 8
        const val DAB_FLOATS = 8

        @JvmStatic
        @Suppress("LongParameterList")
        private external fun nativeWetBegin(
            handle: Long, raw: IntArray, height: FloatArray, wetness: FloatArray, active: IntArray, tileSize: Int,
            media: FloatArray,
        ): Boolean

        @JvmStatic
        private external fun nativeWetBuffer(handle: Long, which: Int, data: FloatArray?): Boolean

        @JvmStatic
        private external fun nativeWetOp(handle: Long, op: Int, args: FloatArray): Boolean

        @JvmStatic
        private external fun nativeWetRead(
            handle: Long, raw: IntArray, height: FloatArray, wetness: FloatArray,
        ): Boolean

        @JvmStatic
        private external fun nativeWetReadFlags(handle: Long, out: IntArray): Boolean
    }
}
