package com.hereliesaz.graffux

import android.content.Context
import android.os.Build
import com.hereliesaz.graffitixr.data.strokedata.StrokeDataStore
import com.hereliesaz.graffitixr.data.strokedata.StrokeRelayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sends finished stroke-data session files (StrokeDataStore) to the stroke relay, which commits
 * them to the repository's `stroke-data` branch for training (tools/stroke-model). Each file is
 * deleted once the relay has it. The caller checks the user's opt-in first; no token is involved.
 * A transient failure keeps the file for the next attempt; a file the relay rejects for good (bad
 * name, too large, not stroke data) is deleted so it can't block every file behind it.
 */
class StrokeDataUploader(
    private val context: Context,
    private val relay: StrokeRelayClient = StrokeRelayClient(),
) {
    /** Returns how many files were uploaded; stops at the first transient failure. */
    suspend fun uploadPending(): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            var sent = 0
            val device = deviceLabel(Build.MODEL)
            for (file in StrokeDataStore.get(context).pending()) {
                val error = relay.upload(device, file.name, file.readBytes()).exceptionOrNull()
                if (error != null && error !is StrokeRelayClient.RejectedException) break
                file.delete()
                if (error == null) sent++
            }
            sent
        }
    }

    companion object {
        /** One upload at a time: the launch upload and the Settings button would otherwise race. */
        private val lock = Mutex()
        private const val MAX_DEVICE = 48

        /** The relay's device segment: 1-48 of `[A-Za-z0-9-]`, never empty. */
        internal fun deviceLabel(model: String?): String =
            model.orEmpty().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(MAX_DEVICE).trim('-')
                .ifEmpty { "unknown" }
    }
}
