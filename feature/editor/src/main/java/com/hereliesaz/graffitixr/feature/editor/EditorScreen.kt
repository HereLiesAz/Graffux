// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorScreen.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.VectorShape
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

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
 * AR/SLAM/co-op/camera in GraffitiXR's MainScreen is intentionally left out; Graffux is a pure 2D
 * design editor, so the layer render is always the plain 2D path.
 */
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by vm.uiState.collectAsState()
    val strings = rememberAppStrings()

    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    val activeLayerLocked = activeLayer?.isImageLocked == true

    Box(
        modifier = modifier
            .fillMaxSize()
            // Fixed dark workspace so the artboard (the document, its own fill) reads as a distinct
            // page rather than blending into an all-black canvas.
            .background(WorkspaceColor)
    ) {
        // Infinite-canvas camera: pans/zooms the layer stack + artboard together (identity = no-op).
        // Screen-space overlays below (gestures, selection, panels) stay OUTSIDE it, in screen space.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = uiState.viewportZoom
                    scaleY = uiState.viewportZoom
                    rotationZ = uiState.viewportRotation
                    translationX = uiState.viewportOffset.x
                    translationY = uiState.viewportOffset.y
                    transformOrigin = TransformOrigin(0f, 0f)
                }
        ) {
        // 0. Page — the document's own fill (canvasBackground) drawn at the artboard, behind layers,
        // so the empty document is a visible page on the darker workspace.
        ArtboardPage(
            documentWidth = uiState.documentWidth,
            documentHeight = uiState.documentHeight,
            color = uiState.canvasBackground,
            modifier = Modifier.fillMaxSize(),
        )
        // 0b. Infinite reference grid — thin, always visible over the page/workspace on any background,
        // panning/zooming/rotating with the canvas. Above the page fill, below the layers, so an empty
        // document shows the grid but painting covers it.
        InfiniteGrid(
            zoom = uiState.viewportZoom,
            offset = uiState.viewportOffset,
            rotationDeg = uiState.viewportRotation,
            modifier = Modifier.fillMaxSize(),
        )
        // 1. Layer stack render.
        uiState.layers.filter { it.isVisible }.forEach { layer ->
            key(layer.id) {
                // Vector layer: drawn from its shapes via Canvas. (A raster layer's bitmap path below
                // no-ops for it, since a vector layer carries no bitmap.)
                if (layer.shapes.isNotEmpty()) {
                    VectorLayerContent(layer, modifier = Modifier.fillMaxSize())
                }
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

        // 1c. Snap guides — world-space alignment lines shown while dragging a layer. Drawn inside the
        // camera container so they pan/zoom/rotate with the workspace. Purely visual.
        if (uiState.snapGuidesX.isNotEmpty() || uiState.snapGuidesY.isNotEmpty()) {
            SnapGuides(
                guidesX = uiState.snapGuidesX,
                guidesY = uiState.snapGuidesY,
                modifier = Modifier.fillMaxSize(),
            )
        }
        } // end infinite-canvas camera container

        // 2. Transform / tap gestures — only when no brush tool is active.
        if (uiState.activeTool == Tool.NONE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(uiState.activeLayerId, activeLayerLocked) {
                        detectTapGestures(
                            onDoubleTap = { vm.onCycleRotationAxis() },
                            // Tap selects the layer under the finger (or dismisses panels on a miss).
                            onTap = { offset ->
                                vm.onCanvasTap(offset, size.width.toFloat(), size.height.toFloat())
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        // Infinite-canvas navigation vs. object move:
                        //  • two+ fingers  → pan + zoom the CAMERA (about the pinch centroid)
                        //  • one finger on the active layer → move that layer (history-bracketed)
                        //  • one finger on empty space      → pan the CAMERA
                        // Handle drags are claimed by the SelectionHandles layer above (consumed),
                        // so they never reach here.
                        //
                        // Keyed on Unit (never restarts): the per-gesture state (layers, viewport,
                        // active layer) is read fresh from the view-model at each touch-down. Keying on
                        // viewport would cancel + relaunch this block on every pan frame — which drops
                        // the in-flight drag and makes panning/zooming feel dead.
                        awaitEachGesture {
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val down = awaitFirstDown(requireUnconsumed = true)
                            val st = vm.uiState.value
                            val active = st.layers.find { it.id == st.activeLayerId }
                            val startOnActiveLayer = active != null && !active.isImageLocked &&
                                CanvasHitTest.topHit(
                                    st.layers, down.position, w, h,
                                    st.viewportOffset, st.viewportZoom, st.viewportRotation,
                                ) == st.activeLayerId
                            var movingObject = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (pressed == 0) break
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                val centroid = event.calculateCentroid()
                                when {
                                    pressed >= 2 -> {
                                        // Two fingers → pan + zoom + rotate the whole canvas (Procreate-style).
                                        if (centroid != Offset.Unspecified)
                                            vm.onViewportPanZoom(pan, zoom, centroid, event.calculateRotation())
                                    }
                                    startOnActiveLayer -> {
                                        if (!movingObject) { vm.onGestureStart(); movingObject = true }
                                        vm.onTransformGesture(pan, 1f, 0f, w, h)
                                    }
                                    else -> {
                                        if (centroid != Offset.Unspecified) vm.onViewportPanZoom(pan, 1f, centroid)
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                            if (movingObject) vm.onGestureEnd()
                        }
                    }
            )
        }

        // 2b. Selection outline — the active layer's transformed bounding box, so the picked shape is
        // visible. Purely visual (no pointer input); only while a transform tool is active.
        if (uiState.activeTool == Tool.NONE) {
            SelectionOverlay(
                activeLayer = activeLayer,
                viewportOffset = uiState.viewportOffset,
                viewportZoom = uiState.viewportZoom,
                viewportRotation = uiState.viewportRotation,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 2c. Resize handles — dragging a corner scales the active layer about its centre (a
        // single-finger "pinch", reusing onTransformGesture). Sits ABOVE the pan-gesture box so a
        // drag that starts on a handle is claimed here; a drag elsewhere isn't consumed, so panning
        // still works. Only when a layer is active and no brush tool is selected.
        if (uiState.activeTool == Tool.NONE && activeLayer != null && !activeLayerLocked) {
            SelectionHandles(
                activeLayer = activeLayer,
                viewportOffset = uiState.viewportOffset,
                viewportZoom = uiState.viewportZoom,
                viewportRotation = uiState.viewportRotation,
                onGestureStart = { vm.onGestureStart() },
                onResize = { zoom -> vm.onTransformGesture(Offset.Zero, zoom, 0f) },
                onRotate = { deg -> vm.onTransformGesture(Offset.Zero, 1f, deg) },
                onGestureEnd = { vm.onGestureEnd() },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 3. Brush touch layer — full-screen, in true screen coordinates (no graphicsLayer here; the
        // layer render already applies the transform, and EditorViewModel.onStrokePoint maps screen
        // space back to bitmap pixels). Active only when a RASTER tool is selected on an unlocked
        // layer — the vector PEN has its own capture layer below.
        if (activeLayer != null && !activeLayerLocked &&
            uiState.activeTool != Tool.NONE && uiState.activeTool != Tool.PEN
        ) {
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

        // 3b. Pen (vector) capture layer — a freeform drag traces a live poly-line that is committed
        // as an open PATH vector layer on release. Needs no active layer (it makes a new one). Runs
        // in screen space; onCommitPenPath maps the points back through the camera.
        if (uiState.activeTool == Tool.PEN) {
            PenCanvas(
                color = uiState.activeColor,
                strokeWidthPx = uiState.brushSize,
                onCommit = { pts, cw, ch -> vm.onCommitPenPath(pts, cw, ch) },
                modifier = Modifier.fillMaxSize(),
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

        // 4b. Empty-state hint — guide the user to add content when the document is blank.
        if (uiState.layers.isEmpty() && !uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Tap the icon, then File → New or Open\nto start your first layer.",
                    color = Color.White.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 4b2. Rulers — persistent thin bars along the top and left edges whose tick marks track the
        // world grid (so they follow the canvas as it pans, zooms, and rotates). Screen-space overlay;
        // purely visual (a Canvas consumes no touches).
        Rulers(
            zoom = uiState.viewportZoom,
            offset = uiState.viewportOffset,
            rotationDeg = uiState.viewportRotation,
        )

        // 4c. Viewport controls — floating canvas controls in the bottom corners (out of the rail, the
        // way GraffitiXR anchors them): fit/reset the view on the left, undo/redo on the right. Each
        // button shows only when it can act, so a clean canvas stays uncluttered.
        ViewportControls(
            uiState = uiState,
            onReset = { vm.resetViewport() },
            onUndo = { vm.onUndoClicked() },
            onRedo = { vm.onRedoClicked() },
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
 * Draws the selection outline: the active layer's transformed content bounding box (four corners
 * from [CanvasHitTest.layerScreenCorners] connected cyclically), so the user can see which layer a
 * canvas tap selected. Non-interactive; nothing is drawn when there is no active layer.
 */
@Composable
private fun SelectionOverlay(
    activeLayer: Layer?,
    viewportOffset: Offset,
    viewportZoom: Float,
    viewportRotation: Float,
    modifier: Modifier = Modifier,
) {
    if (activeLayer == null) return
    Canvas(modifier) {
        val corners = CanvasHitTest.layerScreenCorners(
            activeLayer, size.width, size.height, viewportOffset, viewportZoom, viewportRotation,
        ) ?: return@Canvas
        val stroke = 2.dp.toPx()
        val color = Color(0xFF00E5FF) // cyan, matches the rail accent
        for (i in corners.indices) {
            drawLine(color, corners[i], corners[(i + 1) % corners.size], strokeWidth = stroke)
        }
    }
}

/**
 * Interactive transform handles on the selected layer's bounding box. Two corner handles, following
 * the box as it (and the camera) rotate:
 *  • **resize** — the bottom-right corner (`corners[2]`), drawn as a filled square. Dragging it scales
 *    the layer about its centre (distance-from-pivot ratio) via the [onResize] "zoom" path.
 *  • **rotate** — the adjacent top-right corner (`corners[1]`), drawn as a ring. Dragging it spins the
 *    layer about its centre (swept angle) via [onRotate].
 *
 * Both reuse the proven [EditorViewModel.onTransformGesture] lifecycle ([onGestureStart]/[onGestureEnd]
 * bracket history + persistence). A drag that starts on neither handle is left unconsumed, so the
 * pan-gesture layer below still handles it. The box outline itself is drawn by [SelectionOverlay].
 *
 * Device-tuning note: the handle touch radius and the render-transform pivot basis (shared with
 * [CanvasHitTest]) are derived, not yet verified on a device.
 */
@Composable
private fun SelectionHandles(
    activeLayer: Layer,
    viewportOffset: Offset,
    viewportZoom: Float,
    viewportRotation: Float,
    onGestureStart: () -> Unit,
    onResize: (zoom: Float) -> Unit,
    onRotate: (degrees: Float) -> Unit,
    onGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 24.dp.toPx() }
    Canvas(
        modifier = modifier.pointerInput(activeLayer.id, viewportOffset, viewportZoom, viewportRotation) {
            awaitEachGesture {
                val corners = CanvasHitTest.layerScreenCorners(
                    activeLayer, size.width.toFloat(), size.height.toFloat(),
                    viewportOffset, viewportZoom, viewportRotation,
                ) ?: return@awaitEachGesture
                val down = awaitFirstDown(requireUnconsumed = true)
                val pivot = CanvasHitTest.boxCenter(corners)
                val resizeHandle = corners[2] // bottom-right
                val rotateHandle = corners[1] // top-right (adjacent)

                val onRotateHandle = (down.position - rotateHandle).getDistance() <= handleRadiusPx
                // Resize only if not already on the rotate handle (they can crowd on a tiny box).
                val onResizeHandle = !onRotateHandle &&
                    (down.position - resizeHandle).getDistance() <= handleRadiusPx
                if (!onRotateHandle && !onResizeHandle) return@awaitEachGesture

                down.consume()
                onGestureStart()
                var prevPos = down.position
                var prevDist = (down.position - pivot).getDistance().coerceAtLeast(1f)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    if (onRotateHandle) {
                        onRotate(CanvasHitTest.angleDeltaDegrees(pivot, prevPos, change.position))
                    } else {
                        val curDist = (change.position - pivot).getDistance().coerceAtLeast(1f)
                        onResize(curDist / prevDist)
                        prevDist = curDist
                    }
                    prevPos = change.position
                    change.consume()
                }
                onGestureEnd()
            }
        },
    ) {
        val corners = CanvasHitTest.layerScreenCorners(
            activeLayer, size.width, size.height, viewportOffset, viewportZoom, viewportRotation,
        ) ?: return@Canvas
        val accent = Color(0xFF00E5FF)
        val r = 7.dp.toPx()
        // Resize handle (bottom-right): a filled square.
        val br = corners[2]
        drawRect(accent, topLeft = Offset(br.x - r, br.y - r), size = Size(2 * r, 2 * r))
        // Rotate handle (top-right): a ring.
        val tr = corners[1]
        drawCircle(accent, radius = r, center = tr, style = Stroke(width = 2.dp.toPx()))
        drawCircle(accent, radius = r * 0.35f, center = tr)
    }
}

/**
 * Freeform vector pen capture. A single-finger drag traces a poly-line, previewed live in the brush
 * colour; on release the traced screen points are handed to [onCommit] (with the canvas size) to be
 * turned into an open PATH vector layer. Full-screen and in screen space — the camera mapping happens
 * in the view-model. A stroke of fewer than two points is ignored.
 */
@Composable
private fun PenCanvas(
    color: Color,
    strokeWidthPx: Float,
    onCommit: (points: List<Offset>, canvasWidth: Float, canvasHeight: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val points = remember { mutableStateListOf<Offset>() }
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                points.clear()
                val down = awaitFirstDown(requireUnconsumed = false)
                points.add(down.position)
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    points.add(change.position)
                    change.consume()
                }
                if (points.size >= 2) onCommit(points.toList(), size.width.toFloat(), size.height.toFloat())
                points.clear()
            }
        },
    ) {
        if (points.size >= 2) {
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            }
            drawPath(path, color, style = Stroke(width = strokeWidthPx))
        }
    }
}

/**
 * Draws the active snap guide lines — world-space alignment lines shown while dragging a layer, in
 * the camera container's coordinate space: full-height verticals at [guidesX], full-width horizontals
 * at [guidesY]. Non-interactive.
 */
@Composable
private fun SnapGuides(guidesX: List<Float>, guidesY: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val color = Color(0xFFFF3D7F) // magenta, distinct from the cyan selection accent
        val stroke = 1.dp.toPx()
        guidesX.forEach { x -> drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke) }
        guidesY.forEach { y -> drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke) }
    }
}

/**
 * Floating canvas controls anchored in the bottom corners (out of the nav rail, the way GraffitiXR
 * places them): fit/reset the infinite-canvas view on the bottom-left, undo and redo on the bottom-
 * right. Each button appears only when it can do something — reset only off the identity view, undo/
 * redo only with history — so a fresh, un-panned canvas shows nothing. Placed directly in the editor's
 * root [Box] so only the buttons themselves capture touches; the rest of the canvas keeps its gestures.
 */
@Composable
private fun BoxScope.ViewportControls(
    uiState: EditorUiState,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    val chip = Color.Black.copy(alpha = 0.35f)
    val viewMoved = uiState.viewportZoom != 1f ||
        uiState.viewportOffset != Offset.Zero ||
        uiState.viewportRotation != 0f
    if (viewMoved) {
        IconButton(
            onClick = onReset,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .background(chip, CircleShape),
        ) {
            Icon(Icons.Filled.FitScreen, contentDescription = "Fit view to screen", tint = Color.White)
        }
    }
    Row(
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (uiState.undoCount > 0) {
            IconButton(onClick = onUndo, modifier = Modifier.background(chip, CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = Color.White)
            }
        }
        if (uiState.redoCount > 0) {
            IconButton(onClick = onRedo, modifier = Modifier.background(chip, CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = Color.White)
            }
        }
    }
}

/**
 * A thin, always-visible reference grid across the whole infinite canvas. Rendered inside the camera
 * container in world space, so it pans, zooms, and rotates with the artwork. The visible world region
 * is recovered by inverse-mapping the four screen corners (`local = R(-θ)·((screen − offset)/zoom)`),
 * so only on-screen lines are drawn — it's genuinely infinite, not a giant fixed mesh. A mid-grey at
 * low alpha reads on any page or workspace colour; the stroke is `1/zoom` so it stays hairline-thin
 * on screen at any zoom.
 */
@Composable
private fun InfiniteGrid(
    zoom: Float,
    offset: Offset,
    rotationDeg: Float,
    modifier: Modifier = Modifier,
    spacing: Float = 48f,
    color: Color = Color(0x33808080),
) {
    Canvas(modifier) {
        if (spacing <= 0f) return@Canvas   // guard the tick loops against a non-positive step
        val z = zoom.coerceAtLeast(1e-4f)
        val rad = (-rotationDeg * PI / 180f).toFloat()
        val cs = cos(rad); val sn = sin(rad)
        fun toLocal(sx: Float, sy: Float): Offset {
            val px = (sx - offset.x) / z
            val py = (sy - offset.y) / z
            return Offset(px * cs - py * sn, px * sn + py * cs)
        }
        val corners = listOf(
            toLocal(0f, 0f), toLocal(size.width, 0f),
            toLocal(0f, size.height), toLocal(size.width, size.height),
        )
        val minX = corners.minOf { it.x }; val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }; val maxY = corners.maxOf { it.y }
        // Bail if zoomed so far out the grid would be a dense smear (also a loop-count guard).
        if ((maxX - minX) / spacing > 2000f || (maxY - minY) / spacing > 2000f) return@Canvas
        val stroke = 1f / z
        var x = floor(minX / spacing) * spacing
        while (x <= maxX) { drawLine(color, Offset(x, minY), Offset(x, maxY), strokeWidth = stroke); x += spacing }
        var y = floor(minY / spacing) * spacing
        while (y <= maxY) { drawLine(color, Offset(minX, y), Offset(maxX, y), strokeWidth = stroke); y += spacing }
    }
}

/**
 * Persistent rulers along the top and left edges whose ticks track the world grid. The camera map is
 * `screen = offset + zoom·R(θ)·world`, so along the top edge (`y = 0`) the world-x coordinate is an
 * affine function of screen-x — `worldX = A·x + B`, `A = cosθ/zoom` — and a tick for each grid line
 * `worldX = k·spacing` lands at `x = (k·spacing − B)/A`. The left edge is symmetric in y. This makes
 * the ticks follow the grid exactly as the canvas pans, zooms, and rotates; near a right-angle turn
 * (`cosθ ≈ 0`) that family of lines runs parallel to the edge and is simply skipped.
 */
@Composable
private fun BoxScope.Rulers(
    zoom: Float,
    offset: Offset,
    rotationDeg: Float,
    spacing: Float = 48f,
) {
    val thickness = with(LocalDensity.current) { 14.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        val barColor = Color(0xCC1A1A1A)
        val tickColor = Color(0xFFB0B0B0)
        val z = zoom.coerceAtLeast(1e-4f)
        val rad = (rotationDeg * PI / 180f).toFloat()
        val cosT = cos(rad); val sinT = sin(rad)
        val w = size.width; val h = size.height

        drawRect(barColor, topLeft = Offset(0f, 0f), size = Size(w, thickness))
        drawRect(barColor, topLeft = Offset(0f, 0f), size = Size(thickness, h))

        // Top ruler: worldX = A·x + B along y = 0.
        val a = cosT / z
        val b = -(cosT * offset.x + sinT * offset.y) / z
        if (abs(a) > 1e-6f && spacing / abs(a) >= 6f) {
            val kLo = floor(minOf(b, a * w + b) / spacing).toInt()
            val kHi = ceil(maxOf(b, a * w + b) / spacing).toInt()
            if (kHi - kLo <= 1000) for (k in kLo..kHi) {
                val x = (k * spacing - b) / a
                if (x in 0f..w) drawLine(tickColor, Offset(x, 0f), Offset(x, thickness), strokeWidth = 1f)
            }
        }
        // Left ruler: worldY = A'·y + B' along x = 0.
        val a2 = cosT / z
        val b2 = (sinT * offset.x - cosT * offset.y) / z
        if (abs(a2) > 1e-6f && spacing / abs(a2) >= 6f) {
            val mLo = floor(minOf(b2, a2 * h + b2) / spacing).toInt()
            val mHi = ceil(maxOf(b2, a2 * h + b2) / spacing).toInt()
            if (mHi - mLo <= 1000) for (m in mLo..mHi) {
                val y = (m * spacing - b2) / a2
                if (y in 0f..h) drawLine(tickColor, Offset(0f, y), Offset(thickness, y), strokeWidth = 1f)
            }
        }
    }
}

/** The fixed dark workspace behind the artboard, so the document reads as a distinct page. */
private val WorkspaceColor = Color(0xFF2B2B2B)

/** The centered, aspect-fit artboard rectangle for a [docW]×[docH] document within a [w]×[h] canvas,
 *  as `[left, top, width, height]`. Shared by the page fill and the frame outline. */
private fun artboardRectIn(w: Float, h: Float, docW: Int, docH: Int): FloatArray {
    val docAspect = docW.toFloat() / docH.toFloat()
    val canvasAspect = w / h
    val rectW: Float
    val rectH: Float
    if (docAspect > canvasAspect) { rectW = w; rectH = w / docAspect } else { rectH = h; rectW = h * docAspect }
    return floatArrayOf((w - rectW) / 2f, (h - rectH) / 2f, rectW, rectH)
}

/**
 * Fills the artboard with the document's own [color] (canvasBackground), behind the layers — so an
 * empty document shows up as a page on the darker workspace instead of blending into black.
 */
@Composable
private fun ArtboardPage(documentWidth: Int, documentHeight: Int, color: Color, modifier: Modifier = Modifier) {
    if (documentWidth <= 0 || documentHeight <= 0) return
    Canvas(modifier) {
        val r = artboardRectIn(size.width, size.height, documentWidth, documentHeight)
        val topLeft = Offset(r[0], r[1])
        val rectSize = Size(r[2], r[3])
        if (color.alpha == 0f) {
            // Transparent document: paint the standard checkerboard so the empty page reads as
            // "see-through" instead of blending into the workspace. Cells clipped to the artboard rect.
            drawRect(Color(0xFFCFCFCF), topLeft = topLeft, size = rectSize)
            val cell = 16f
            val cols = ceil(r[2] / cell).toInt()
            val rows = ceil(r[3] / cell).toInt()
            for (cy in 0 until rows) for (cx in 0 until cols) {
                if ((cx + cy) % 2 == 0) {
                    val x = r[0] + cx * cell
                    val y = r[1] + cy * cell
                    drawRect(
                        Color(0xFF9E9E9E),
                        topLeft = Offset(x, y),
                        size = Size(minOf(cell, r[0] + r[2] - x), minOf(cell, r[1] + r[3] - y)),
                    )
                }
            }
        } else {
            drawRect(color, topLeft = topLeft, size = rectSize)
        }
    }
}

/**
 * Outlines the artboard (the document bounds) over the layer stack, so the export region is always
 * visible. The page fill ([ArtboardPage]) + the [WorkspaceColor] workspace provide the contrast, so
 * this is just a crisp border now. Non-interactive.
 */
@Composable
private fun ArtboardFrame(
    documentWidth: Int,
    documentHeight: Int,
    modifier: Modifier = Modifier,
) {
    if (documentWidth <= 0 || documentHeight <= 0) return
    val border = Color.White.copy(alpha = 0.85f)
    Canvas(modifier) {
        val r = artboardRectIn(size.width, size.height, documentWidth, documentHeight)
        drawRect(color = border, topLeft = Offset(r[0], r[1]), size = Size(r[2], r[3]), style = Stroke(width = 2f))
    }
}

/**
 * Renders a vector [layer] — its [VectorShape]s drawn via [Canvas], centered in the surface and
 * transformed by the layer's offset/scale/rotation/opacity/blend exactly like a raster layer's
 * bitmap. Shapes are defined in the layer's local pixel space centered on the origin.
 */
@Composable
private fun VectorLayerContent(layer: Layer, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.graphicsLayer {
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
        }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        layer.shapes.forEach { drawVectorShape(it, cx, cy) }
    }
}

/** Draws a single [shape] centered at ([cx], [cy]) in the current [DrawScope]. */
private fun DrawScope.drawVectorShape(shape: VectorShape, cx: Float, cy: Float) {
    val w = shape.width
    val h = shape.height
    when (shape.kind) {
        ShapeKind.RECTANGLE -> {
            val topLeft = Offset(cx - w / 2f, cy - h / 2f)
            val sz = Size(w, h)
            val radius = CornerRadius(shape.cornerRadius, shape.cornerRadius)
            if (shape.hasFill) drawRoundRect(Color(shape.fillArgb.toInt()), topLeft, sz, radius)
            if (shape.hasStroke) drawRoundRect(Color(shape.strokeArgb.toInt()), topLeft, sz, radius, style = Stroke(shape.strokeWidth))
        }
        ShapeKind.ELLIPSE -> {
            val topLeft = Offset(cx - w / 2f, cy - h / 2f)
            val sz = Size(w, h)
            if (shape.hasFill) drawOval(Color(shape.fillArgb.toInt()), topLeft, sz)
            if (shape.hasStroke) drawOval(Color(shape.strokeArgb.toInt()), topLeft, sz, style = Stroke(shape.strokeWidth))
        }
        ShapeKind.LINE -> {
            if (shape.hasStroke) {
                drawLine(
                    Color(shape.strokeArgb.toInt()),
                    Offset(cx - w / 2f, cy),
                    Offset(cx + w / 2f, cy),
                    strokeWidth = shape.strokeWidth,
                )
            }
        }
        ShapeKind.POLYGON -> {
            val path = polygonPath(cx, cy, w, h, shape.sides)
            if (shape.hasFill) drawPath(path, Color(shape.fillArgb.toInt()))
            if (shape.hasStroke) drawPath(path, Color(shape.strokeArgb.toInt()), style = Stroke(shape.strokeWidth))
        }
        ShapeKind.PATH -> {
            val path = pointsPath(shape.points, cx, cy, shape.closed) ?: return
            if (shape.hasFill) drawPath(path, Color(shape.fillArgb.toInt()))
            if (shape.hasStroke) drawPath(path, Color(shape.strokeArgb.toInt()), style = Stroke(shape.strokeWidth))
        }
    }
}

/**
 * A [Path] through the interleaved local [points] (`[x0,y0,…]`, centered on origin), offset to
 * ([cx], [cy]). [close]s the figure when asked. Null when there are fewer than two points. The
 * export path builds the identical figure with an android.graphics.Path.
 */
private fun pointsPath(points: List<Float>, cx: Float, cy: Float, close: Boolean): Path? {
    if (points.size < 4) return null
    return Path().apply {
        moveTo(cx + points[0], cy + points[1])
        var i = 2
        while (i + 1 < points.size) {
            lineTo(cx + points[i], cy + points[i + 1])
            i += 2
        }
        if (close) close()
    }
}

/**
 * A regular [sides]-gon centered at ([cx], [cy]) inscribed in a [w]×[h] box, first vertex pointing
 * up. [sides] is floored at 3. Shared shape math; the export path builds the same figure with an
 * android.graphics.Path.
 */
private fun polygonPath(cx: Float, cy: Float, w: Float, h: Float, sides: Int): Path {
    val n = sides.coerceAtLeast(3)
    val rx = w / 2f
    val ry = h / 2f
    return Path().apply {
        for (i in 0 until n) {
            val a = -Math.PI / 2 + i * 2 * Math.PI / n
            val px = cx + rx * kotlin.math.cos(a).toFloat()
            val py = cy + ry * kotlin.math.sin(a).toFloat()
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}
