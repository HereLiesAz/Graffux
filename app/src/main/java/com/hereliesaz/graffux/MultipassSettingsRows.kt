package com.hereliesaz.graffux

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
        title = "Progressive stroke rendering",
        subtitle = "Each dab appears immediately as a quick draft, then refines automatically. " +
            "GPU capability, thermal headroom and the remaining dab backlog determine how quickly " +
            "full quality arrives. There is no drying timer. Applies to the next stroke.",
        options = listOf(false, true),
        selected = settings.enabled,
        label = { if (it) "On" else "Off" },
        onSelect = { update(settings.copy(enabled = it)) },
    )
}
