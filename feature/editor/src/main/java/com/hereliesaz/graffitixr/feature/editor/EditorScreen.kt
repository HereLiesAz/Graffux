// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorScreen.kt
package com.hereliesaz.graffitixr.feature.editor

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.detectSmartOverlayGestures
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import kotlinx.coroutines.launch

/**
 * The full standalone 2D editor screen — the single source of truth hosted by GraffiXR and (later)
 * consumed by GraffitiXR. It composes the four already-migrated pieces of the editor into one
 * self-contained surface:
 *
 *  1. the visible **layer stack** (each [com.hereliesaz.graffitixr.common.model.Layer] drawn with
 *     its transform, tone [ColorFilter], blend mode, and live-stroke swap),
 *  2. **transform/tap gestures** when no brush tool is active,
 *  3. the [DrawingCanvas] brush touch layer when a tool is active, and
 *  4. the [EditorUi] bottom panels (layers / adjustments / color picker),
 *
 * plus a compact icon **tool rail**. Everything AR/SLAM/co-op/camera in GraffitiXR's MainScreen is
 * intentionally left out; GraffiXR forces [EditorMode.DESIGN], so the per-mode whole-design
 * adjustment is always identity and the layer render simplifies to the plain 2D path.
 */
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by vm.uiState.collectAsState()
    val strings = rememberAppStrings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // GraffiXR is a pure 2D editor: force DESIGN mode (EditorViewModel defaults to AR) so the layer
    // render and Design-mode transform gestures apply. Runs once.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.setEditorMode(EditorMode.DESIGN) }

    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    val activeLayerLocked = activeLayer?.isImageLocked == true

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.onAddLayer(it) } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(uiState.canvasBackground)
    ) {
        // 1. Layer stack render.
        uiState.layers.filter { it.isVisible }.forEach { layer ->
            key(layer.id) {
                val isLive = layer.id == uiState.liveStrokeLayerId
                val bmp = if (isLive) uiState.liveStrokeBitmap ?: layer.bitmap else layer.bitmap
                bmp?.let { displayBmp ->
                    val imageBitmap = if (isLive) {
                        val version = uiState.liveStrokeVersion
                        remember(version) { displayBmp.asImageBitmap() }
                    } else {
                        remember(displayBmp) { displayBmp.asImageBitmap() }
                    }
                    // Memoize the colour filter so it isn't rebuilt on every recomposition for every
                    // layer (a per-frame allocation storm) — recompute only when inputs change.
                    val colorFilter = remember(
                        layer.saturation, layer.contrast, layer.brightness,
                        layer.colorBalanceR, layer.colorBalanceG, layer.colorBalanceB,
                        layer.isInverted
                    ) {
                        ColorFilter.colorMatrix(
                            createColorMatrix(
                                saturation = layer.saturation,
                                contrast = layer.contrast,
                                brightness = layer.brightness,
                                colorBalanceR = layer.colorBalanceR,
                                colorBalanceG = layer.colorBalanceG,
                                colorBalanceB = layer.colorBalanceB,
                                isInverted = layer.isInverted
                            )
                        )
                    }
                    // Offscreen compositing is only needed to isolate a non-default blend mode; for
                    // normal (SrcOver) layers, Auto avoids the extra full-screen pass per frame.
                    val needsOffscreen = layer.blendMode != BlendMode.SrcOver
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        colorFilter = colorFilter,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = layer.offset.x
                                translationY = layer.offset.y
                                scaleX = layer.scale
                                scaleY = layer.scale
                                rotationX = layer.rotationX
                                rotationY = layer.rotationY
                                rotationZ = layer.rotationZ
                                alpha = layer.opacity
                                transformOrigin = TransformOrigin.Center
                                blendMode = layer.blendMode
                                compositingStrategy = if (needsOffscreen)
                                    CompositingStrategy.Offscreen
                                else
                                    CompositingStrategy.Auto
                            },
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        // 2. Transform / tap gestures — only when no brush tool is active.
        if (uiState.activeTool == Tool.NONE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(uiState.activeLayerId, activeLayerLocked) {
                        detectTapGestures(
                            onDoubleTap = { vm.onCycleRotationAxis() },
                            onTap = { vm.onDismissPanel() }
                        )
                    }
                    .pointerInput(uiState.activeLayerId, activeLayerLocked) {
                        if (activeLayer != null && !activeLayerLocked) {
                            detectSmartOverlayGestures(
                                getValidBounds = {
                                    Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
                                },
                                onGestureStart = { vm.onGestureStart() },
                                onGestureEnd = { vm.onGestureEnd() },
                                onGesture = { _, pan, zoom, rotation ->
                                    vm.onTransformGesture(pan, zoom, rotation)
                                }
                            )
                        }
                    }
            )
        }

        // 3. Brush touch layer — full-screen, in true screen coordinates (no graphicsLayer here; the
        // layer render already applies the transform, and EditorViewModel.onStrokePoint maps screen
        // space back to bitmap pixels). Active only when a tool is selected on an unlocked layer.
        if (activeLayer != null && !activeLayerLocked && uiState.activeTool != Tool.NONE) {
            DrawingCanvas(
                activeTool = uiState.activeTool,
                brushSize = uiState.brushSize,
                activeColor = uiState.activeColor,
                layerBitmapKey = activeLayer.bitmap,
                modifier = Modifier.fillMaxSize(),
                onStrokeStart = { offset, size -> vm.onStrokeStart(offset, size) },
                onStrokePoint = { offset -> vm.onStrokePoint(offset) },
                onStrokeEnd = { vm.onStrokeEnd() }
            )
        }

        // 4. Bottom panels overlay (layers list, adjustment knobs, colour picker).
        EditorUi(
            actions = vm,
            uiState = uiState,
            isTouchLocked = false,
            showUnlockInstructions = false,
            strings = strings,
            isCapturingTarget = false
        )

        // 5. Tool rail — on the handedness-preferred side.
        EditorToolRail(
            activeTool = uiState.activeTool,
            activePanel = uiState.activePanel,
            canUndo = uiState.undoCount > 0,
            canRedo = uiState.redoCount > 0,
            onPickImage = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onAddBlankLayer = { vm.onAddBlankLayer() },
            onToggleLayers = { vm.onLayersClicked() },
            onSelectTool = { vm.setActiveTool(it) },
            onColor = { vm.onColorClicked() },
            onAdjust = { vm.onAdjustClicked() },
            onUndo = { vm.onUndoClicked() },
            onRedo = { vm.onRedoClicked() },
            onSave = { vm.saveProject() },
            onExport = { vm.exportImage() },
            onShare = {
                // Interop hand-off: composite the design to a content:// Uri and offer it to any app
                // (e.g. GraffitiXR to project in AR). No-op silently if there's nothing to share.
                scope.launch {
                    val uri = vm.exportForShare() ?: return@launch
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }
            },
            modifier = Modifier
                .align(if (uiState.isRightHanded) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(8.dp)
        )

        // 6. Loading indicator.
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun EditorToolRail(
    activeTool: Tool,
    activePanel: EditorPanel,
    canUndo: Boolean,
    canRedo: Boolean,
    onPickImage: () -> Unit,
    onAddBlankLayer: () -> Unit,
    onToggleLayers: () -> Unit,
    onSelectTool: (Tool) -> Unit,
    onColor: () -> Unit,
    onAdjust: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RailButton(Icons.Filled.Image, "Add image", onClick = onPickImage)
        RailButton(Icons.Filled.Add, "Add blank layer", onClick = onAddBlankLayer)
        RailButton(Icons.Filled.Layers, "Layers", selected = activePanel == EditorPanel.LAYERS, onClick = onToggleLayers)
        RailButton(Icons.Filled.PanTool, "Move", selected = activeTool == Tool.NONE, onClick = { onSelectTool(Tool.NONE) })
        RailButton(Icons.Filled.Brush, "Brush", selected = activeTool == Tool.BRUSH, onClick = { onSelectTool(Tool.BRUSH) })
        RailButton(Icons.Filled.Palette, "Colour", selected = activePanel == EditorPanel.COLOR, onClick = onColor)
        RailButton(Icons.Filled.Tune, "Adjust", selected = activePanel == EditorPanel.ADJUST, onClick = onAdjust)
        RailButton(Icons.AutoMirrored.Filled.Undo, "Undo", enabled = canUndo, onClick = onUndo)
        RailButton(Icons.AutoMirrored.Filled.Redo, "Redo", enabled = canRedo, onClick = onRedo)
        RailButton(Icons.Filled.Save, "Save", onClick = onSave)
        RailButton(Icons.Filled.FileDownload, "Export", onClick = onExport)
        RailButton(Icons.Filled.Share, "Share", onClick = onShare)
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.3f),
        ),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}
