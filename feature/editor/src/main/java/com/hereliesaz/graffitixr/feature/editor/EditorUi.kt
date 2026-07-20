// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorUi.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
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
            ColorPickerDialog(
                currentColor = uiState.activeColor,
                history = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Black, Color.White),
                onSelectColor = { actions.setActiveColor(it) },
                onDismiss = { actions.onColorPickerDismissed() },
                strings = strings
            )
        }

        GestureFeedback(
            state = uiState,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Layer List Panel (Conditional)
            if (uiState.activePanel == EditorPanel.LAYERS) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    LayersPanel(
                        layers = uiState.layers,
                        activeLayerId = uiState.activeLayerId,
                        onSelectLayer = actions::onLayerActivated,
                        onToggleVisibility = actions::onToggleVisibility,
                        onDuplicate = actions::onLayerDuplicated,
                        onDelete = actions::onLayerRemoved,
                        onRename = actions::onLayerRenamed,
                        onClose = { actions.onDismissPanel() },
                        strings = strings
                    )
                }
            }

            // 1b. Precise numeric transform panel (X / Y / Scale / Rotation of the active layer).
            if (uiState.activePanel == EditorPanel.TRANSFORM) {
                TransformPanel(
                    activeLayer = uiState.layers.find { it.id == uiState.activeLayerId },
                    actions = actions,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

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
                    hasHistory = uiState.undoCount > 0 || uiState.redoCount > 0,
                    undoCount = uiState.undoCount,
                    redoCount = uiState.redoCount,
                    isRightHanded = uiState.isRightHanded,
                    isCapturingTarget = isCapturingTarget,
                    activeLayer = overlayLayer,
                    showUndoRedo = true
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
                onUndo = actions::onUndoClicked,
                onRedo = actions::onRedoClicked,
                onAdjustmentStart = actions::onAdjustmentStart,
                onAdjustmentEnd = actions::onAdjustmentEnd,
                strings = strings,
                showSegmentationSlider = uiState.isSegmenting,
                segmentationInfluence = uiState.segmentationInfluence,
                onSegmentationInfluenceChange = { actions.setSegmentationInfluence(it) },
                onSegmentationDismiss = { actions.onConfirmSegmentation() },
                onSegmentationCancel = { actions.onCancelSegmentation() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LayersPanel(
    layers: List<Layer>,
    activeLayerId: String?,
    onSelectLayer: (String) -> Unit,
    onToggleVisibility: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onClose: () -> Unit,
    strings: AppStrings
) {
    // Which row is in inline-rename mode (long-press a name to start), and its edit buffer.
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(strings.editor.layers, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                strings.common.close,
                color = Color.Gray,
                modifier = Modifier.clickable { onClose() }.padding(8.dp)
            )
        }
        // Top layer first (layers render bottom-to-top, so reverse for the panel).
        LazyColumn(Modifier.fillMaxWidth()) {
            items(layers.reversed()) { layer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (layer.id == activeLayerId) Color.Gray.copy(alpha = 0.3f) else Color.Transparent),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onToggleVisibility(layer.id) }) {
                        Icon(
                            imageVector = if (layer.isVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (layer.isVisible) strings.editor.hideLayer else strings.editor.showLayer,
                            tint = if (layer.isVisible) Color.White else Color.Gray,
                        )
                    }
                    if (editingId == layer.id) {
                        OutlinedTextField(
                            value = editingName,
                            onValueChange = { editingName = it },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                onRename(layer.id, editingName.trim().ifBlank { layer.name })
                                editingId = null
                            }),
                        )
                    } else {
                        Text(
                            layer.name,
                            color = Color.White,
                            modifier = Modifier
                                .weight(1f)
                                .combinedClickable(
                                    onClick = { onSelectLayer(layer.id) },
                                    onLongClick = {
                                        editingId = layer.id
                                        editingName = layer.name
                                    },
                                )
                                .padding(vertical = 12.dp)
                        )
                    }
                    IconButton(onClick = { onDuplicate(layer.id) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = strings.editor.duplicate, tint = Color.White)
                    }
                    IconButton(onClick = { onDelete(layer.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = strings.editor.delete, tint = Color.White)
                    }
                }
            }
        }
    }
}
