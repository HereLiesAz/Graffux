// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorScreen.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.detectSmartOverlayGestures
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings

/**
 * The full standalone 2D editor screen — the single source of truth hosted by GraffiXR and (later)
 * consumed by GraffitiXR. It composes the migrated pieces of the editor into one self-contained
 * surface:
 *
 *  1. the visible **layer stack** (each [com.hereliesaz.graffitixr.common.model.Layer] drawn with
 *     its transform, tone [ColorFilter], blend mode, and live-stroke swap),
 *  2. **transform/tap gestures** when no brush tool is active,
 *  3. the [DrawingCanvas] brush touch layer when a tool is active, and
 *  4. the [EditorUi] bottom panels (layers / adjustments / color picker).
 *
 * The tool rail is NOT drawn here: GraffiXR wraps this screen in the app's [AzNavRail] host
 * (`AzHostActivityLayout`, in MainActivity), which renders this canvas as its full-screen `background`
 * and puts the design tools on the rail — the same arrangement GraffitiXR uses. Everything
 * AR/SLAM/co-op/camera in GraffitiXR's MainScreen is intentionally left out; GraffiXR forces
 * [EditorMode.DESIGN], so the per-mode whole-design adjustment is always identity and the layer
 * render simplifies to the plain 2D path.
 */
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by vm.uiState.collectAsState()
    val strings = rememberAppStrings()

    // GraffiXR is a pure 2D editor: force DESIGN mode (EditorViewModel defaults to AR) so the layer
    // render and Design-mode transform gestures apply. Runs once.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.setEditorMode(EditorMode.DESIGN) }

    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    val activeLayerLocked = activeLayer?.isImageLocked == true

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

        // 1b. Artboard frame — the document bounds. A non-interactive overlay: dims the workspace
        // outside the centered, aspect-fit document rect and outlines it, so the fixed output size is
        // always visible. Purely visual (no pointer input) — gestures/drawing below are unaffected.
        ArtboardFrame(
            documentWidth = uiState.documentWidth,
            documentHeight = uiState.documentHeight,
            modifier = Modifier.fillMaxSize(),
        )

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

        // 5. Loading indicator.
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

/**
 * Draws the artboard: the centered, aspect-fit rectangle matching the document's [documentWidth] :
 * [documentHeight] ratio, with the surrounding workspace dimmed and the bounds outlined. Non-
 * interactive (a plain [Canvas] with no pointer input), so gestures and drawing below are untouched.
 */
@Composable
private fun ArtboardFrame(
    documentWidth: Int,
    documentHeight: Int,
    modifier: Modifier = Modifier,
) {
    if (documentWidth <= 0 || documentHeight <= 0) return
    val scrim = Color.Black.copy(alpha = 0.5f)
    val border = Color.White.copy(alpha = 0.7f)
    Canvas(modifier) {
        val docAspect = documentWidth.toFloat() / documentHeight.toFloat()
        val canvasAspect = size.width / size.height
        val rectW: Float
        val rectH: Float
        if (docAspect > canvasAspect) {
            rectW = size.width
            rectH = size.width / docAspect
        } else {
            rectH = size.height
            rectW = size.height * docAspect
        }
        val left = (size.width - rectW) / 2f
        val top = (size.height - rectH) / 2f
        // Scrim bands around the artboard (top / bottom / left / right).
        if (top > 0f) drawRect(scrim, Offset(0f, 0f), Size(size.width, top))
        if (top + rectH < size.height) drawRect(scrim, Offset(0f, top + rectH), Size(size.width, size.height - top - rectH))
        if (left > 0f) drawRect(scrim, Offset(0f, top), Size(left, rectH))
        if (left + rectW < size.width) drawRect(scrim, Offset(left + rectW, top), Size(size.width - left - rectW, rectH))
        // Document outline.
        drawRect(color = border, topLeft = Offset(left, top), size = Size(rectW, rectH), style = Stroke(width = 2f))
    }
}
