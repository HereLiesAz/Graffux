// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorUi.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.design.components.AdjustmentsPanel
import com.hereliesaz.graffitixr.design.components.AdjustmentsState
import com.hereliesaz.graffitixr.feature.editor.ui.GestureFeedback
import com.hereliesaz.graffitixr.design.theme.AppStrings

@Composable
fun EditorUi(
    actions: EditorViewModel,
    uiState: EditorUiState,
    isTouchLocked: Boolean,
    showUnlockInstructions: Boolean,
    strings: AppStrings,
    isCapturingTarget: Boolean = false
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = Modifier.fillMaxSize()) {

        if (uiState.showColorPicker) {
            BackgroundColorDialog(
                current = uiState.activeColor,
                onSelect = { color ->
                    actions.setActiveColor(color)
                    actions.onColorPickerDismissed()
                },
                onDismiss = { actions.onColorPickerDismissed() },
            )
        }

        GestureFeedback(
            state = uiState,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
        )

        Column(
            // Clear the system navigation bar: without this the Transform panel's Scale/Rotate row and
            // the adjustment knobs' labels were drawn underneath it and simply couldn't be read or hit.
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1b. Precise numeric transform panel (X / Y / Scale / Rotation of the active layer).
            if (uiState.activePanel == EditorPanel.TRANSFORM) {
                TransformPanel(
                    activeLayer = uiState.layers.find { it.id == uiState.activeLayerId },
                    actions = actions,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // 1c. Extensions Panel
            if (uiState.activePanel == EditorPanel.EXTENSIONS) {
                val installedExtensions by actions.installedExtensions.collectAsState()
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    ExtensionsPanel(
                        extensions = installedExtensions,
                        onSelect = actions::onExtensionSelected,
                        onClose = { actions.onDismissPanel() }
                    )
                }
            }

            // No floating Layers panel. The layers live in their own floating rail now (an
            // AzNavRail unattached host at FLOATING — see MainActivity's `grp.layers`), each with a
            // hidden menu carrying that layer's own tools. `EditorPanel.LAYERS` was still rendered
            // here, and the `LayersPanel` composable behind it was complete and correct, but nothing
            // in the app ever dispatched `ToggleLayersPanel`: there was no way to open it and no way
            // to reach the code. Two layer lists, one of them unreachable, is worse than one.

            // 2. Integrated Adjustments Panel (Knobs + Undo/Redo/Magic)
            val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
            val overlayLayer = activeLayer?.let {
                OverlayLayer(
                    id = it.id,
                    name = it.name,
                    uri = it.uri ?: android.net.Uri.EMPTY,
                    opacity = it.opacity,
                    brightness = it.brightness,
                    contrast = it.contrast,
                    saturation = it.saturation,
                    colorBalanceR = it.colorBalanceR,
                    colorBalanceG = it.colorBalanceG,
                    colorBalanceB = it.colorBalanceB,
                    isImageLocked = it.isImageLocked,
                    isInverted = it.isInverted
                )
            }

            // The adjust knobs act on the active layer; they're rail-triggered via the Adjust panel.
            AdjustmentsPanel(
                state = AdjustmentsState(
                    hideUiForCapture = uiState.hideUiForCapture,
                    isTouchLocked = isTouchLocked,
                    hasImage = uiState.layers.isNotEmpty(),
                    isRightHanded = uiState.isRightHanded,
                    isCapturingTarget = isCapturingTarget,
                    activeLayer = overlayLayer,
                ),
                showKnobs = !isCapturingTarget && uiState.activePanel == EditorPanel.ADJUST,
                showColorBalance = uiState.activePanel == EditorPanel.COLOR,
                isLandscape = isLandscape,
                screenHeight = screenHeight,
                onOpacityChange = actions::onOpacityChanged,
                onBrightnessChange = actions::onBrightnessChanged,
                onContrastChange = actions::onContrastChanged,
                onSaturationChange = actions::onSaturationChanged,
                onColorBalanceRChange = actions::onColorBalanceRChanged,
                onColorBalanceGChange = actions::onColorBalanceGChanged,
                onColorBalanceBChange = actions::onColorBalanceBChanged,
                onAdjustmentStart = actions::onAdjustmentStart,
                onAdjustmentEnd = actions::onAdjustmentEnd,
                strings = strings,
            )
        }
    }
}
