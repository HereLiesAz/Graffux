package com.hereliesaz.graffux

import android.content.Context
import android.os.Build
import com.hereliesaz.graffitixr.data.strokedata.StrokeDataStore
import com.hereliesaz.graffitixr.data.strokedata.StrokeRelayClient

/**
 * Sends finished stroke-data session files (StrokeDataStore) to the stroke relay, which commits
 * them to the repository's `stroke-data` branch for training (tools/stroke-model). Each file is
 * deleted once the relay has it. The caller checks the user's opt-in first; no token is involved.
 * A failed upload keeps the file for the next attempt.
 */
class StrokeDataUploader(
    private val context: Context,
    private val relay: StrokeRelayClient = StrokeRelayClient(),
) {
    /** Returns how many files were uploaded; stops at the first failure and keeps the rest. */
    suspend fun uploadPending(): Int {
        var sent = 0
        val device = Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-")
        for (file in StrokeDataStore.get(context).pending()) {
            if (relay.upload(device, file.name, file.readBytes()).isFailure) break
            file.delete()
            sent++
        }
        return sent
    }
}
