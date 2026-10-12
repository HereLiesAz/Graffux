package com.hereliesaz.graffitixr.nativebridge

/**
 * Undo on the GPU for document layers (core/wgpu-engine undo.rs). A stroke's bind keeps the layer
 * as it was; after the stroke commits, [keep] copies the rectangle it painted, as it was before
 * the stroke, into a GPU snapshot. [swap] exchanges a snapshot with its layer: once to undo, again
 * to redo. No pixels cross to the CPU.
 *
 * A snapshot lives on the document engine's native handle and goes with it; [Ref] carries both,
 * and a ref whose handle is gone simply fails. Every call runs on [GpuRenderThread].
 */
object GpuUndoSnapshots {

    /** A snapshot [id] on native [handle]. */
    data class Ref internal constructor(internal val handle: Long, val id: Long)

    /**
     * Keeps [rect] ({x, y, w, h}) of [stroke]'s layer from before [stroke], which has committed.
     * Null when the handle is gone or a later stroke has bound since.
     */
    fun keep(stroke: ResidentStroke, rect: IntArray): Ref? = GpuRenderThread.call {
        val id = if (stroke.handle in GpuStampEngine.liveWgpuHandles) {
            nativeDocSnapshot(stroke.handle, KEEP, longArrayOf(stroke.layerKey, stroke.session, 0L), rect.copyOf())
        } else {
            0L
        }
        if (id == 0L) null else Ref(stroke.handle, id)
    }

    /**
     * Exchanges [ref] with its layer if the layer is still at generation [expected], tagging it
     * [generation]. False (nothing changed) when the handle or snapshot is gone or the layer holds
     * other content.
     */
    fun swap(ref: Ref, expected: Long, generation: Long): Boolean = GpuRenderThread.call {
        ref.handle in GpuStampEngine.liveWgpuHandles &&
            nativeDocSnapshot(ref.handle, SWAP, longArrayOf(ref.id, expected, generation), IntArray(RECT_INTS)) != 0L
    }

    /** Frees [ref] (queued). */
    fun drop(ref: Ref) {
        GpuRenderThread.post {
            if (ref.handle in GpuStampEngine.liveWgpuHandles) {
                nativeDocSnapshot(ref.handle, DROP, longArrayOf(ref.id, 0L, 0L), null)
            }
        }
    }

    private const val KEEP = 0
    private const val SWAP = 1
    private const val DROP = 2
    private const val RECT_INTS = 4

    @JvmStatic
    private external fun nativeDocSnapshot(handle: Long, op: Int, args: LongArray, rect: IntArray?): Long
}
