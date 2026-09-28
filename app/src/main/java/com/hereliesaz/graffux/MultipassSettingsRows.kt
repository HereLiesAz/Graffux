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
            .putFloat(GpuStampEngine.KEY_MULTIPASS_TRANSITION_MS, next.transitionMs)
            .apply()
    }
    ChoiceRow(
        title = "Multipass drying (experimental)",
        subtitle = "wgpu engine only. Each dab appears at once as a quick draft of the same brush, " +
            "then settles to full quality as the device catches up, like paint drying. The saved " +
            "result is identical either way. Applies to the next stroke.",
        options = listOf(false, true),
        selected = settings.enabled,
        label = { if (it) "On" else "Off" },
        onSelect = { update(settings.copy(enabled = it)) },
    )
    if (settings.enabled) {
        ChoiceRow(
            title = "Drying transition",
            subtitle = "How long a finished area takes to fade in. 0 ms swaps it in at once.",
            options = MultipassSettings.TRANSITION_CHOICES_MS,
            selected = settings.transitionMs,
            label = { "${it.toInt()} ms" },
            onSelect = { update(settings.copy(transitionMs = it)) },
        )
    }
}
