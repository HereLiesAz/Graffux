package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap

/**
 * The document store on a wgpu [engine] (core/wgpu-engine docstore.rs): the open document's layers
 * kept on the GPU as their canonical copy, keyed by layer key, each with an engine-owned
 * generation that every write bumps. Layer images cross as full-layer ARGB_8888 bitmaps; a rect
 * read or write touches only that rectangle. Every call runs on [GpuRenderThread] and returns
 * false / 0 when the engine is not initialized or the library lacks the store.
 */
class GpuDocumentLayers(private val engine: GpuStampEngine) {

    private inline fun <T> call(fallback: T, crossinline block: (Long) -> T): T = synchronized(engine) {
        val handle = engine.handleForDocument
        if (handle == 0L) fallback else GpuRenderThread.call { block(handle) }
    }

    /** Puts the engine into document mode. Call once after [GpuStampEngine.init]. */
    fun open(): Boolean = call(false) { nativeDocOpen(it) }

    /** Writes the whole of layer [key] from [bitmap] (creating it); its new generation, 0 = failure. */
    fun put(key: Long, bitmap: Bitmap): Long = call(0L) { nativeDocLayer(it, PUT, key, 0L, bitmap) }

    /** A transparent layer [key] (created if new); its new generation, 0 = failure. */
    fun clear(key: Long): Long = call(0L) { nativeDocLayer(it, CLEAR, key, 0L, null) }

    /** Layer [src] duplicated into [dst] on the GPU; [dst]'s new generation, 0 = failure. */
    fun copy(src: Long, dst: Long): Long = call(0L) { nativeDocLayer(it, COPY, dst, src, null) }

    /** Writes [rect] ({x, y, w, h}) of layer [key] from the same rectangle of [bitmap]. */
    fun write(key: Long, bitmap: Bitmap, rect: IntArray): Long = call(0L) { nativeDocWrite(it, key, bitmap, rect) }

    /** Forgets layer [key]. */
    fun drop(key: Long): Boolean = call(false) { nativeDocDrop(it, key) }

    /** Starts a stroke on layer [key]; the stroke's session, 0 = failure. */
    fun bind(key: Long): Long = call(0L) { nativeDocBind(it, key) }

    /**
     * Commits the stroke [session] on layer [key] in place; [rect] receives the {x, y, w, h} it
     * painted. The layer's new generation, 0 if [session] is no longer the layer's stroke.
     */
    fun commit(key: Long, session: Long, rect: IntArray): Long =
        call(0L) { nativeDocCommit(it, key, session, rect) }

    /** Reads [rect] of layer [key] into the same rectangle of [bitmap] (a full-layer mirror). */
    fun read(key: Long, bitmap: Bitmap, rect: IntArray): Boolean = call(false) { nativeDocRead(it, key, bitmap, rect) }

    private companion object {
        // nativeDocLayer ops (4 = generation, unused here).
        const val PUT = 0
        const val CLEAR = 1
        const val COPY = 3

        @JvmStatic
private external fun nativeDocOpen(handle: Long): Boolean
        @JvmStatic
        private external fun nativeDocLayer(handle: Long, op: Int, key: Long, other: Long, bitmap: Bitmap?): Long
        @JvmStatic
        private external fun nativeDocWrite(handle: Long, key: Long, bitmap: Bitmap, rect: IntArray): Long
        @JvmStatic
        private external fun nativeDocDrop(handle: Long, key: Long): Boolean
        @JvmStatic
        private external fun nativeDocBind(handle: Long, key: Long): Long
        @JvmStatic
        private external fun nativeDocCommit(handle: Long, key: Long, session: Long, rect: IntArray): Long
        @JvmStatic
        private external fun nativeDocRead(handle: Long, key: Long, bitmap: Bitmap, rect: IntArray): Boolean
    }
}
