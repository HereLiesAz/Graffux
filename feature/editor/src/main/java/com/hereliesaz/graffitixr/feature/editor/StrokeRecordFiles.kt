package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.StampStrokeRecord
import com.hereliesaz.graffitixr.common.azphalt.StrokeRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** The file side of [StrokeRecordSync]: reading and repairing `strokes.json`, keeping the original, and the cap. */
internal object StrokeRecordFiles {
    /** What was read: the bytes as stored and what the repairing reader made of them (null: unusable). */
    class Loaded(val bytes: ByteArray, val repaired: StrokeRecording.Companion.Repaired?) {
        /** Something was lost, or everything: the original must be kept before a save can overwrite it. */
        val needsBackup: Boolean get() = repaired == null || repaired.dropped > 0
    }

    /** Reads and decodes the project's strokes file (call on the IO dispatcher); null when there is none. */
    suspend fun load(
        store: StrokeRecordSync.Store,
        projectId: String,
        decoder: (String) -> StrokeRecording.Companion.Repaired?,
    ): Loaded? {
        val bytes = store.read(projectId, StrokeRecordSync.FILE) ?: return null
        return Loaded(bytes, runCatching { decoder(String(bytes)) }.getOrNull())
    }

    /** Writes the original bytes to `strokes.json.bak`; true only if that worked (a failure is reported). */
    suspend fun keepOriginal(store: StrokeRecordSync.Store, projectId: String, bytes: ByteArray): Boolean =
        runCatching { store.write(projectId, "${StrokeRecordSync.FILE}.bak", bytes) }
            .onFailure { if (it is CancellationException) throw it else store.onError(it) }
            .isSuccess

    /** Drops the oldest strokes until [list] is within [limits]. */
    fun trim(list: MutableList<StampStrokeRecord>, limits: StrokeRecordSync.Limits) {
        var samples = list.sumOf { it.samples.size }
        while (list.size > limits.maxStrokes || samples > limits.maxSamples) samples -= list.removeAt(0).samples.size
    }

    /** Drops the oldest idle project states beyond [keep] (never [current], never one with outstanding work). */
    fun evictIdle(states: MutableMap<String, *>, idle: (String) -> Boolean, current: String?, keep: Int) {
        val candidates = states.keys.filter { it != current && idle(it) }
        candidates.dropLast(keep).forEach { states.remove(it) }
    }
}

/**
 * The write side of stroke recording, shared by EVERY [StrokeRecordSync] in the process: a ViewModel being cleared and
 * the next one being created overlap, and their writes to one project's file must stay ordered and visible to each
 * other. One lock around encode and write; the newest snapshot (a process-wide sequence number) is the one that
 * stays; a project whose original could not be preserved is refused; and every write in flight is registered by
 * project so a final flush can wait for it and a restore can wait before it reads.
 */
internal object StrokeWriteQueue {
    val lock = Mutex()
    val lastWritten = ConcurrentHashMap<String, Long>()
    val unpreserved: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val seq = AtomicLong()
    private val active = ConcurrentHashMap<String, MutableList<CompletableDeferred<Unit>>>()

    fun nextSeq(): Long = seq.incrementAndGet()

    /** Registers a write of [projectId] that is about to start; call [finish] when it ends. */
    fun begin(projectId: String): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { active.getOrPut(projectId) { CopyOnWriteArrayList() } += it }

    fun finish(projectId: String, mark: CompletableDeferred<Unit>) {
        active[projectId]?.remove(mark)
        mark.complete(Unit)
    }

    /** Waits for every write of [projectId] now in flight, from any sync instance. */
    suspend fun awaitWrites(projectId: String) {
        active[projectId]?.toList()?.forEach { it.await() }
    }

    /** Waits for every write in flight for any project. */
    suspend fun awaitAll() {
        active.values.toList().flatMap { it.toList() }.forEach { it.await() }
    }

    /** For tests: forgets everything. */
    fun reset() {
        lastWritten.clear()
        unpreserved.clear()
        active.clear()
    }
}
