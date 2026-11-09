package com.hereliesaz.graffux

import androidx.compose.ui.res.stringResource
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hereliesaz.graffitixr.common.azphalt.wgpu.MultipassSettings
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine

/**
 * Multipass drying (experimental, wgpu only, off by default): each dab shows at once as a draft of
 * the same stamp, and its full-quality version settles in whenever the device gets to it. The
 * transition is only the ease when a finished result is swapped in; nothing waits for it.
 */
@Suppress("FunctionNaming") // Composable naming, as everywhere else in this file.
@Composable
internal fun MultipassRows(prefs: SharedPreferences) {
    var settings by remember { mutableStateOf(GpuStampEngine.multipass) }
    fun update(next: MultipassSettings) {
        settings = next
        GpuStampEngine.multipass = next
        prefs.edit()
            .putBoolean(GpuStampEngine.KEY_MULTIPASS, next.enabled)
            .apply()
    }
    ChoiceRow(
        title = stringResource(R.string.settings_multipass),
        subtitle = stringResource(R.string.settings_multipass_hint),
        options = listOf(false, true),
        selected = settings.enabled,
        label = { stringResource(if (it) R.string.settings_on else R.string.settings_off) },
        onSelect = { update(settings.copy(enabled = it)) },
    )
}
