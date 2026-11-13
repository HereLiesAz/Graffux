package com.hereliesaz.graffux

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.hereliesaz.graffitixr.data.strokedata.StrokeDataStore
import com.hereliesaz.graffitixr.feature.editor.strokedata.STROKE_DATA_PREFS
import com.hereliesaz.graffitixr.feature.editor.strokedata.StrokeContributionConsent
import com.hereliesaz.graffitixr.data.strokedata.StrokeRelayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sends finished stroke-data session files (StrokeDataStore) to the stroke relay, which stores
 * them in a private bucket for training (tools/stroke-model). Each file is deleted once the relay has
 * it. Nothing is sent unless the switch is on and the 16+ declaration was made
 * ([StrokeContributionConsent.isContributing]); no token is involved.
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
            val prefs = context.getSharedPreferences(STROKE_DATA_PREFS, Context.MODE_PRIVATE)
            if (!StrokeContributionConsent.isContributing(prefs)) return@withContext 0
            var sent = 0
            val device = deviceLabel(Build.MODEL)
            for (file in StrokeDataStore.get(context).pending()) {
                val outcome = handle(prefs, device, file)
                if (outcome == Outcome.UPLOADED) sent++
                if (outcome == Outcome.RETRY_LATER) break
            }
            sent
        }
    }

    private enum class Outcome { UPLOADED, DISCARDED, RETRY_LATER }

    /** One file: sent and deleted, deleted unsent (pre-declaration or rejected for good), or kept for later. */
    private suspend fun handle(prefs: SharedPreferences, device: String, file: File): Outcome {
        // Recorded before the 16+ declaration: never sent, even if the declaration came first.
        val send = StrokeContributionConsent.mayUpload(prefs, file.name)
        val error = if (send) relay.upload(device, file.name, file.readBytes()).exceptionOrNull() else null
        val keep = send && error != null && error !is StrokeRelayClient.RejectedException
        if (!keep) file.delete()
        return when {
            keep -> Outcome.RETRY_LATER
            send && error == null -> Outcome.UPLOADED
            else -> Outcome.DISCARDED
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
