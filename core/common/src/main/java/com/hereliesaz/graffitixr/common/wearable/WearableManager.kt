package com.hereliesaz.graffitixr.common.wearable

import com.hereliesaz.graffitixr.common.sensor.PhoneSensorSource
import com.hereliesaz.graffitixr.common.sensor.SensorSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WearableManager @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards SmartGlassProvider>,
    private val phoneSensorSource: PhoneSensorSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeSensorSource: MutableStateFlow<SensorSource> = MutableStateFlow(phoneSensorSource)
    val activeSensorSource: StateFlow<SensorSource> = _activeSensorSource

    @Volatile private var activeProvider: SmartGlassProvider? = null
    // The connectionState collect never completes (hot StateFlow); track it so re-activating or
    // deactivating cancels the previous collector instead of leaking one per call.
    @Volatile private var connectionJob: Job? = null

    fun listProviders(): List<SmartGlassProvider> = providers.toList()

    // Synchronized: activate()/deactivate() each mutate activeProvider + connectionJob across
    // several steps. Without a lock, a fast switch (e.g. two activate() calls, or activate()
    // racing deactivate()) could interleave — e.g. deactivate() reading a stale connectionJob, or
    // overwriting activeProvider after a newer activate() already wrote it — leaving a live
    // collector routing state for a provider that was just told to disconnect.
    @Synchronized
    fun activate(provider: SmartGlassProvider) {
        connectionJob?.cancel()
        // Previously missing: switching providers without an intervening deactivate() left the old
        // provider's connection (USB polling, IMU stream, spatial display) open indefinitely while
        // _activeSensorSource now pointed at the new one — two live hardware sessions at once.
        activeProvider?.disconnect()
        activeProvider = provider
        provider.connect()
        connectionJob = scope.launch {
            provider.connectionState.collect { state ->
                _activeSensorSource.value = when (state) {
                    is ConnectionState.Connected -> provider
                    is ConnectionState.Disconnected,
                    is ConnectionState.Error -> phoneSensorSource
                    else -> _activeSensorSource.value
                }
            }
        }
    }

    @Synchronized
    fun deactivate() {
        connectionJob?.cancel()
        connectionJob = null
        activeProvider?.disconnect()
        activeProvider = null
        _activeSensorSource.value = phoneSensorSource
    }
}
