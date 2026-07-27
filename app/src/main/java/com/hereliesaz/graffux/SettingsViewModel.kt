package com.hereliesaz.graffux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.DispatcherProvider
import com.hereliesaz.graffitixr.common.model.AppLanguage
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Graffux [SettingsScreen] with the design-relevant slice of [SettingsRepository] —
 * handedness (which side the nav rail docks to), measurement units (used by the rulers), and app
 * language, plus a tutorial reset. The AR-only preferences the repository also holds aren't surfaced
 * here, since Graffux is a design-only host. Each flow is cached as a [StateFlow] for the UI; writes
 * are persisted off the main thread.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    val isRightHanded: StateFlow<Boolean> =
        settings.isRightHanded.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val isImperialUnits: StateFlow<Boolean> =
        settings.isImperialUnits.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val language: StateFlow<AppLanguage> =
        settings.language.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLanguage.SYSTEM)

    fun setRightHanded(isRight: Boolean) = viewModelScope.launch(dispatchers.io) {
        settings.setRightHanded(isRight)
    }

    fun setImperialUnits(imperial: Boolean) = viewModelScope.launch(dispatchers.io) {
        settings.setImperialUnits(imperial)
    }

    fun setLanguage(language: AppLanguage) = viewModelScope.launch(dispatchers.io) {
        settings.setLanguage(language)
    }

    /** Re-arm the first-run tutorial/hint flows. */
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

    fun resetTutorials() = viewModelScope.launch(dispatchers.io) {
        settings.clearCompletedTutorials()
    }
}
