package com.hereliesaz.graffitixr.feature.editor

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Shared Engine 2 scheduling primitive for every live brush renderer.
 *
 * Producers append losslessly and return immediately. Exactly one consumer coroutine is allowed
 * to run at a time; it drains everything that accumulated during the previous render as one batch.
 * Therefore expensive brushes increase batch size instead of growing a job backlog and pointer
 * latency. [isCurrent] is checked before every drain/publish cycle so a worker from a superseded
 * stroke cannot consume work belonging to the next one.
 */
internal class AzphaltLiveBatchWorker<T>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val isCurrent: () -> Boolean,
    private val renderBatch: suspend (List<T>) -> Unit,
) {
    private val lock = Any()
    private val pending = AzphaltPendingBatchQueue<T>()
    private var job: Job? = null

    fun append(items: Collection<T>) {
        if (items.isEmpty()) return
        pending.append(items)
        ensureRunning()
    }

    fun append(item: T) {
        pending.append(item)
        ensureRunning()
    }

    fun cancelAndClear() {
        synchronized(lock) {
            pending.clear()
            job?.cancel()
            job = null
        }
    }

    val pendingCount: Int get() = pending.size
    val isActive: Boolean get() = synchronized(lock) { job?.isActive == true }

    private fun ensureRunning() {
        synchronized(lock) {
            if (job?.isActive == true) return
            job = scope.launch(dispatcher) {
                try {
                    while (isCurrent()) {
                        val batch = pending.drain()
                        if (batch.isEmpty()) {
                            synchronized(lock) {
                                if (!isCurrent()) return@launch
                                if (pending.isEmpty) {
                                    job = null
                                    return@launch
                                }
                            }
                            continue
                        }
                        renderBatch(batch)
                    }
                } finally {
                    synchronized(lock) {
                        // Do not clear a replacement job created after this one became stale.
                        if (job === coroutineContext[Job]) job = null
                    }
                }
            }
        }
    }
}
