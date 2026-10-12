package com.hereliesaz.graffitixr.nativebridge

/**
 * A stroke confined to the selection on the GPU at commit (core/wgpu-engine selection.rs), on a
 * wgpu [engine]: the selection's coverage is uploaded once per selection change and kept on the
 * engine; a stroke keeps the layer as it starts ([takeShadow]) and blends its paint back through
 * the coverage before it commits ([confine]). False when the engine is not initialized or the
 * library lacks it.
 */
class GpuSelection(private val engine: GpuStampEngine) {

    private inline fun call(crossinline block: (Long) -> Boolean): Boolean = synchronized(engine) {
        val handle = engine.handleForDocument
        handle != 0L && GpuRenderThread.call { block(handle) }
    }

    /** The selection's coverage, one byte per layer pixel (0 outside, 255 inside); null clears it. */
    fun set(coverage: ByteArray?): Boolean = call { nativeSetSelection(it, coverage) }

    /** Keeps the layer as it is now, before the stroke's first dab. */
    fun takeShadow(): Boolean = call { nativeSelectionOp(it, TAKE_SHADOW) }

    /** Blends the stroke back over its shadow through the coverage: [feathered] or the hard clip. */
    fun confine(feathered: Boolean): Boolean = call { nativeSelectionOp(it, if (feathered) FEATHERED else HARD) }

    private companion object {
        const val TAKE_SHADOW = 0
        const val HARD = 1
        const val FEATHERED = 2

        @JvmStatic
        private external fun nativeSetSelection(handle: Long, coverage: ByteArray?): Boolean

        @JvmStatic
        private external fun nativeSelectionOp(handle: Long, op: Int): Boolean
    }
}
