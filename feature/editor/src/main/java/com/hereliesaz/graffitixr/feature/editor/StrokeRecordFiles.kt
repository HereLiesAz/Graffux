package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.StampStrokeRecord
import com.hereliesaz.graffitixr.common.azphalt.StrokeRecording
import kotlinx.coroutines.CancellationException

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
}
