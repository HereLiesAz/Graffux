package com.hereliesaz.graffux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.DEFAULT_GESTURE_MAPPING
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import com.hereliesaz.graffitixr.data.prediction.PredictionReportRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Graffux [SettingsScreen] with the design-relevant slice of [SettingsRepository] —
 * handedness (which side the nav rail docks to), measurement units (used by the rulers), whether
 * the brush size slider is locked to a constant on-screen footprint, the two performance dials and
 * the gesture map, plus a tutorial reset. The AR-only preferences the
 * repository also holds aren't surfaced here, since Graffux is a design-only host. Each flow is
 * cached as a [StateFlow] for the UI; writes are persisted off the main thread.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    // TEMPORARY: stroke-prediction ranking issues. Nullable so tests needn't supply it.
    private val predictionReports: PredictionReportRepository? = null,
) : ViewModel() {

    val predictionReportsConnected: StateFlow<Boolean> =
        predictionReports?.isConnected ?: MutableStateFlow(false)

    /** Result message of the last connect attempt, for the Settings row; null = none yet. */
    private val _predictionReportsStatus = MutableStateFlow<String?>(null)
    val predictionReportsStatus: StateFlow<String?> = _predictionReportsStatus

    fun connectPredictionReports(token: String) = viewModelScope.launch {
        val reports = predictionReports ?: return@launch
        _predictionReportsStatus.value = reports.connect(token).fold(
            onSuccess = { "Connected. Rankings are filed every 25 Brush strokes." },
            onFailure = { it.message ?: "Couldn't connect" },
        )
    }

    /** Sends finished stroke-data files now instead of at the next launch. */
    fun uploadStrokeData(context: android.content.Context) = viewModelScope.launch {
        val reports = predictionReports ?: return@launch
        _predictionReportsStatus.value = "Uploading stroke data..."
        val sent = runCatching { StrokeDataUploader(context.applicationContext, reports).uploadPending() }
        _predictionReportsStatus.value = sent.fold(
            onSuccess = { "Uploaded $it stroke-data file(s)." },
            onFailure = { "Stroke data upload failed: ${it.message}" },
        )
        // Success or partial failure, the set of files still waiting may have changed.
        pendingStrokeData.refresh(context)
    }

    /** Finished stroke-data files not yet uploaded, for the Settings row. */
    val pendingStrokeData = PendingStrokeDataCount(dispatchers)

    fun disconnectPredictionReports() {
        predictionReports?.disconnect()
        _predictionReportsStatus.value = null
    }

    val isRightHanded: StateFlow<Boolean> =
        settings.isRightHanded.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val isImperialUnits: StateFlow<Boolean> =
        settings.isImperialUnits.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // No language flow, and no AppCompatDelegate.setApplicationLocales collector. `:core:design`
    // shipped no translations of its own — the `values-*` directories it carried were GraffitiXR's,
    // down to its `app_name` and its AR-mode copy, and they are gone. Applying a locale that has no
    // resources behind it changes nothing except which language Android *thinks* the UI is in, which
    // is worse than not offering the switch. `SettingsRepository.language` still exists for
    // GraffitiXR, which consumes these same core modules and does have the translations.

    fun setRightHanded(isRight: Boolean) = viewModelScope.launch(dispatchers.io) {
        settings.setRightHanded(isRight)
    }

    fun setImperialUnits(imperial: Boolean) = viewModelScope.launch(dispatchers.io) {
        settings.setImperialUnits(imperial)
    }

    val brushSizeFixedOnScreen: StateFlow<Boolean> =
        settings.brushSizeFixedOnScreen.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setBrushSizeFixedOnScreen(fixed: Boolean) = viewModelScope.launch(dispatchers.io) {
        settings.setBrushSizeFixedOnScreen(fixed)
    }

    /** Settings > Jetpack Ink brush: the round Brush's live stroke drawn by androidx.ink. Off by default. */
    val jetpackInkBrush: StateFlow<Boolean> =
        settings.jetpackInkBrush.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setJetpackInkBrush(enabled: Boolean) = viewModelScope.launch(dispatchers.io) {
        settings.setJetpackInkBrush(enabled)
    }

    /**
     * Performance settings. Both trade fidelity for power and memory, which is a judgement only the
     * person holding the device can make — hence exposed rather than tuned to a fixed guess.
     */
    val inputSampleRateHz: StateFlow<Int> =
        settings.inputSampleRateHz.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 60)

    val canvasRenderScale: StateFlow<Float> =
        settings.canvasRenderScale.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    fun setInputSampleRateHz(hz: Int) = viewModelScope.launch(dispatchers.io) {
        settings.setInputSampleRateHz(hz)
    }

    fun setCanvasRenderScale(scale: Float) = viewModelScope.launch(dispatchers.io) {
        settings.setCanvasRenderScale(scale)
    }

    /** Which action each customizable multi-finger gesture triggers — see [GestureSlot]. */
    val gestureMapping: StateFlow<Map<GestureSlot, GestureAction>> =
        settings.gestureMapping.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_GESTURE_MAPPING)

    fun setGestureAction(slot: GestureSlot, action: GestureAction) = viewModelScope.launch(dispatchers.io) {
        settings.setGestureAction(slot, action)
    }
}
