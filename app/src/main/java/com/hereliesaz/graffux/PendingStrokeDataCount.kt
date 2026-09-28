package com.hereliesaz.graffux

import android.content.Context
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.data.strokedata.StrokeDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * How many finished stroke-data files are waiting to upload, as a [StateFlow] so the Settings row
 * updates when it changes instead of caching a count read once. [SettingsViewModel] refreshes it
 * after every upload; the screen refreshes it on entry and when recording is toggled.
 */
class PendingStrokeDataCount(private val dispatchers: DispatcherProvider) {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    /** Re-reads the count from disk, off the main thread. */
    suspend fun refresh(context: Context) {
        _count.value = withContext(dispatchers.io) {
            StrokeDataStore.get(context.applicationContext).pending().size
        }
    }
}
