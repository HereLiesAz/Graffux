package com.hereliesaz.graffux

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
        title = "Re-run calibration",
        subtitle = "Detected tier: " + tuning.describe() +
            when (state) {
                CalibrationState.RUNNING -> ". Measuring now…"
                CalibrationState.FAILED -> ". The last calibration did not finish; the default is in use."
                else -> "."
            } + " Tap to measure this GPU again in the background (a few seconds; drawing cancels it).",
        onClick = { controller.coordinator.rerun() },
    )
}
