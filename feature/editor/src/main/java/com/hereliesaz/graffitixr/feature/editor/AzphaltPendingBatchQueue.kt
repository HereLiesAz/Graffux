package com.hereliesaz.graffitixr.feature.editor

/**
 * Single-consumer, lossless coalescing queue for live brush work.
 *
 * Input threads append only newly-generated work. The renderer drains all currently pending items
 * in one batch, so a slow frame never creates one coroutine/job per touch sample. Unlike a
 * latest-wins frame snapshot, this queue does not drop paint instructions.
 */
internal class AzphaltPendingBatchQueue<T> {
    private val lock = Any()
    private val pending = ArrayList<T>()

    fun append(items: Collection<T>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            pending.addAll(items)
        }
    }

    fun append(item: T) {
        synchronized(lock) {
            pending.add(item)
        }
    }

    /** Removes and returns everything currently queued as one render batch. */
    fun drain(): List<T> = synchronized(lock) {
        if (pending.isEmpty()) return@synchronized emptyList()
        val result = pending.toList()
        pending.clear()
        result
    }

    fun clear() {
        synchronized(lock) {
            pending.clear()
        }
    }

    val size: Int
        get() = synchronized(lock) { pending.size }

    val isEmpty: Boolean
        get() = size == 0
}
