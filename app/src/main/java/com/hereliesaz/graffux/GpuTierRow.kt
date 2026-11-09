package com.hereliesaz.graffux

import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.graffitixr.feature.editor.gpu.CalibrationState
import com.hereliesaz.graffitixr.feature.editor.gpu.GpuTuningController

/**
 * The GPU tier calibration picked for this device (or the conservative default until it has), with
 * the numbers behind it, and a way to measure again (after a driver update the app detects that
 * itself; this is for when the numbers look wrong).
 */
@Suppress("FunctionNaming") // Composable naming, as everywhere else in this file.
@Composable
internal fun GpuTierRow() {
    val context = LocalContext.current
    val controller = remember(context) { GpuTuningController.get(context) }
    val tuning by controller.tuning.collectAsState()
    val state by controller.coordinator.state.collectAsState()
    ActionRow(
        title = stringResource(R.string.settings_gpu_recalibrate),
        subtitle = stringResource(
            when (state) {
                CalibrationState.RUNNING -> R.string.settings_gpu_tier_measuring
                CalibrationState.FAILED -> R.string.settings_gpu_tier_failed
                else -> R.string.settings_gpu_tier_ready
            },
            tuning.describe(),
        ) + stringResource(R.string.settings_gpu_tier_tap),
        onClick = { controller.coordinator.rerun() },
    )
}
