package com.hereliesaz.graffux

import android.content.Context
import android.os.Build
import com.hereliesaz.graffitixr.data.prediction.PredictionReportRepository
import com.hereliesaz.graffitixr.data.strokedata.StrokeDataStore

/**
 * Sends finished stroke-data session files (StrokeDataStore) to the repository's `stroke-data`
 * branch for training (tools/stroke-model), deleting each once GitHub has it. Needs the Settings
 * token with Contents: read and write. Without a token nothing is sent and files keep accumulating.
 */
class StrokeDataUploader(
    private val context: Context,
    private val reports: PredictionReportRepository,
) {
    /** Returns how many files were uploaded; stops at the first failure and keeps the rest. */
    suspend fun uploadPending(): Int {
        if (!reports.isConnected.value) return 0
        var sent = 0
        for (file in StrokeDataStore.get(context).pending()) {
            val name = "${Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-")}/${file.name}"
            if (reports.uploadStrokeData(name, file.readBytes()).isFailure) break
            file.delete()
            sent++
        }
        return sent
    }
}
