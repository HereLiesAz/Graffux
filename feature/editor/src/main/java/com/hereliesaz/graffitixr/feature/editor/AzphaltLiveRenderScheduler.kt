package com.hereliesaz.graffitixr.feature.editor

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Latest-wins scheduler: live brush rendering can never build an input-sized work queue. */
internal class AzphaltLiveRenderScheduler<T>(
    private val launchWorker: (() -> Unit) -> Unit,
    private val render: (T) -> Unit,
) {
    private val pending = AtomicReference<T?>(null)
    private val running = AtomicBoolean(false)
    fun submit(snapshot: T) { pending.set(snapshot); startWorkerIfNeeded() }
    fun clear() { pending.set(null) }
    private fun startWorkerIfNeeded() {
        if (!running.compareAndSet(false, true)) return
        launchWorker {
            try { while (true) render(pending.getAndSet(null) ?: break) }
            finally { running.set(false); if (pending.get() != null) startWorkerIfNeeded() }
        }
    }
}
