package com.hereliesaz.graffux

import android.content.Context
import com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController

/**
 * The one-time notice for a device below the hardware floor (Android 10 + Vulkan 1.1, the
 * manifest's `<uses-feature>`). Play filters such devices out, so this only reaches a sideloaded
 * install. Nothing is disabled: the GPU stamp engine's init already fails cleanly there and the
 * stroke takes the existing CPU path. The notice just says why drawing may be slower.
 *
 * [takeOnce] only decides and records; the host shows the text through the rail popup, the same
 * place every other notice goes.
 */
object HardwareFloorNotice {
    private const val PREFS = "hardware_floor"
    private const val KEY_SHOWN = "notice_shown"

    const val MESSAGE =
        "This device doesn't report Vulkan 1.1, so Graffux draws with its slower fallback path."

    /** [MESSAGE] the first time this is called on a device below the floor; null otherwise. */
    fun takeOnce(context: Context): String? {
        if (GpuTuningController.meetsHardwareFloor(context)) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOWN, false)) return null
        prefs.edit().putBoolean(KEY_SHOWN, true).apply()
        return MESSAGE
    }
}
