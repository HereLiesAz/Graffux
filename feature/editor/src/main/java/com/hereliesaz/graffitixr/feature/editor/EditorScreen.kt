// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/EditorScreen.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.supportsAlphaLock
import com.hereliesaz.graffitixr.common.model.PathEditing
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import com.hereliesaz.graffitixr.common.model.TransformMode
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.VectorShape
import com.hereliesaz.graffitixr.common.model.LayerNode
import com.hereliesaz.graffitixr.common.model.buildLayerTree
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
    // Non-null when the HOST owns the Procreate history gestures. The host installs the observer
    // above the whole app (see MainActivity) so two- and three-finger taps also register over the
    // nav rail, the floating panels and the onscreen chrome — none of which live inside this
    // screen's subtree, and all of which used to swallow the gesture entirely. This screen then
    // only feeds the shared gate from its drawing surfaces. Null = standalone: it owns both, and
    // the gestures are canvas-only.
    hostStrokeGate: StrokeGate? = null,
) {
    val uiState by vm.uiState.collectAsState()
    val strings = rememberAppStrings()

    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }
    val activeLayerLocked = activeLayer?.isImageLocked == true

    // Coordinates the drawing surfaces with the multi-finger tap observer, so a two-finger tap that
    // cancelled an in-flight stroke doesn't also undo the previous action.
    val ownGate = remember { StrokeGate() }
    val strokeGate = hostStrokeGate ?: ownGate

    // Procreate's history gestures, live in every mode: two-finger tap undoes, three-finger tap
    // redoes by default — but every slot is user-reassignable (Settings), so each fires through
    // performGestureAction against the live mapping rather than a fixed call. A pure observer —
    // consumes nothing, so painting and navigation are unaffected.
    val gestureObserver = if (hostStrokeGate == null) {
        Modifier.multiFingerTaps(strokeGate) { slot, at ->
            performGestureAction(uiState.gestureMapping, slot, vm, at)
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Fixed dark workspace so the artboard (the document, its own fill) reads as a distinct
            // page rather than blending into an all-black canvas.
            .background(WorkspaceColor)
            // The one authoritative measure of the canvas every screen-space geometry is authored
            // against. Reported here rather than inferred from whichever tool last measured itself,
            // so a selection made before the device was rotated can be re-expressed against the
            // canvas it is now being drawn and hit-tested in.
            .onSizeChanged { vm.onCanvasSizeChanged(it) }
            .then(gestureObserver)
            // Two-finger camera navigation, under every tool. On the root rather than as a layer
            // inside it: as an ancestor it is always in the pointer path and is dispatched after
            // the tool surfaces, so it sees exactly the events they declined to consume. Dropped
            // entirely while the quick menu is open, so a gesture aimed at the menu does not also
            // swing the canvas behind it.
            .then(
                if (uiState.quickMenuAt == null) {
                    Modifier.canvasNavigation { pan, zoom, centroid, rotation ->
                        vm.onViewportPanZoom(pan, zoom, centroid, rotation)
                    }
                } else Modifier
            )
    ) {
        // Infinite-canvas camera: pans/zooms the layer stack + artboard together (identity = no-op).
        // Screen-space overlays below (gestures, selection, panels) stay OUTSIDE it, in screen space.
        BoxWithConstraints(
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
            val imageAspect = uiState.documentWidth.toFloat() / uiState.documentHeight.toFloat()
            val screenAspect = maxWidth.value / maxHeight.value
            val renderWidth = if (imageAspect > screenAspect) maxWidth.value else maxHeight.value * imageAspect
            val renderHeight = if (imageAspect > screenAspect) maxWidth.value / imageAspect else maxHeight.value

            val tileIndices = if (uiState.wrapAroundMode) -1..1 else 0..0

            for (dx in tileIndices) {
                for (dy in tileIndices) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset(x = (dx * renderWidth).dp, y = (dy * renderHeight).dp)
                    ) {
                        // 0. Page — the document's own fill
                        ArtboardPage(
                            documentWidth = uiState.documentWidth,
                            documentHeight = uiState.documentHeight,
                            color = uiState.canvasBackground,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // 0b. Infinite reference grid
                        if (dx == 0 && dy == 0) {
                            InfiniteGrid(
                                zoom = uiState.viewportZoom,
                                offset = uiState.viewportOffset,
                                rotationDeg = uiState.viewportRotation,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // 1. Layer stack render.
                        val layerTree = remember(uiState.layers) { buildLayerTree(uiState.layers) }
                        // Animation Assist: every top-level layer is a frame, so only the active
                        // frame draws at full strength and neighbours fade in as onion skins. Off
                        // (the normal editor), every frame stays at 1 and this is a no-op.
                        val frameAlphas = remember(
                            uiState.layers, uiState.isAnimationMode, uiState.activeFrameIndex,
                            uiState.onionSkinEnabled, uiState.onionSkinFrameCount,
                        ) {
                            if (!uiState.isAnimationMode) emptyMap() else AnimationFrames.effectiveOpacities(
                                uiState.layers,
                                uiState.activeFrameIndex,
                                uiState.onionSkinEnabled,
                                uiState.onionSkinFrameCount,
                            )
                        }
                        layerTree.forEach { node ->
                            LayerStackNode(node, uiState, frameAlpha = frameAlphas[node.layer.id] ?: 1f)
                        }
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

        // (Camera navigation is a modifier on the root Box above — see `Modifier.canvasNavigation`.
        // It was a sibling layer here and received no pointer events at all, because Compose stops
        // hit-testing a Box's children at the topmost one that is hit.)

        // 2. Transform / tap gestures — only when no brush tool is active.
        if (uiState.activeTool == Tool.NONE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(uiState.activeLayerId, activeLayerLocked) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                vm.onCanvasDoubleTap(offset, size.width.toFloat(), size.height.toFloat())
                            },
                            // Tap selects the layer under the finger (or dismisses panels on a miss).
                            onTap = { offset ->
                                vm.onCanvasTap(offset, size.width.toFloat(), size.height.toFloat())
                            }
                        )
                    }
                    .pointerInput(Unit) {
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
                                if (pressed >= 2) {
                                    if (movingObject) { vm.onGestureEnd(); movingObject = false }
                                    break
                                }
                                if (startOnActiveLayer) {
                                    if (!movingObject) { vm.onGestureStart(); movingObject = true }
                                    // The pan arrives in screen pixels; a layer's offset is world
                                    // space. Handing the raw screen pan straight through meant that
                                    // at 2× zoom the layer travelled twice as far as the finger, and
                                    // under a rotated camera it slid off at an angle to the drag.
                                    vm.onTransformGesture(
                                        CanvasHitTest.screenDeltaToWorld(
                                            event.calculatePan(), st.viewportZoom, st.viewportRotation,
                                        ),
                                        1f, 0f, w, h,
                                    )
                                    event.changes.forEach { it.consume() }
                                }
                            }
                            if (movingObject) vm.onGestureEnd()
                        }
                    }
            )
        }

        // 2b. Selection outline — the active layer's transformed bounding box, so the active layer is
        // always visibly bounded. Purely visual (no pointer input).
        if (activeLayer != null) {
            SelectionOverlay(
                activeLayer = activeLayer,
                viewportOffset = uiState.viewportOffset,
                viewportZoom = uiState.viewportZoom,
                viewportRotation = uiState.viewportRotation,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 2c. Transform frame — dragging the top-right corner rotates the active layer on the Z axis,
        // the bottom-right corner scales it, and the rest of the frame (the other two corners and the
        // four edges) moves it. Sits above the canvas so frame drags are claimed here.
        if (activeLayer != null && !activeLayerLocked) {
            SelectionHandles(
                activeLayer = activeLayer,
                viewportOffset = uiState.viewportOffset,
                viewportZoom = uiState.viewportZoom,
                viewportRotation = uiState.viewportRotation,
                onGestureStart = { vm.onGestureStart() },
                onResize = { zoom -> vm.onTransformGesture(Offset.Zero, zoom, 0f) },
                onRotate = { deg -> vm.onRotateLayerHandle(deg) },
                // A layer's offset is world space; the drag arrives in screen space, so the camera
                // has to come out of it or the layer outruns the finger at any zoom but 1.
                // The canvas size is passed through so a frame drag snaps to the artboard and the
                // other layers exactly as a drag through the artwork does — onTransformGesture only
                // runs applyMoveSnap when it knows how big the canvas is.
                onMove = { delta ->
                    vm.onTransformGesture(
                        CanvasHitTest.screenDeltaToWorld(
                            delta, uiState.viewportZoom, uiState.viewportRotation,
                        ),
                        1f, 0f,
                        uiState.canvasSize.width.toFloat(), uiState.canvasSize.height.toFloat(),
                    )
                },
                onGestureEnd = { vm.onGestureEnd() },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 3. Brush touch layer — full-screen, in true screen coordinates (no graphicsLayer here; the
        // layer render already applies the transform, and EditorViewModel.onStrokePoint maps screen
        // space back to bitmap pixels). Active only when a RASTER tool is selected on an unlocked
        // layer — the vector PEN has its own capture layer below.
        if (activeLayer != null && !activeLayerLocked &&
            uiState.activeTool != Tool.NONE && uiState.activeTool != Tool.PEN &&
            uiState.activeTool != Tool.SELECT
        ) {
            DrawingCanvas(
                activeTool = uiState.activeTool,
                brushSize = uiState.brushSize,
                activeColor = uiState.activeColor,
                layerBitmapKey = activeLayer.bitmap,
                gate = strokeGate,
                modifier = Modifier.fillMaxSize(),
                onStrokeStart = { offset, size -> vm.onStrokeStart(offset, size) },
                onStrokePoint = { offset -> vm.onStrokePoint(offset) },
                onStrokeEnd = { vm.onStrokeEnd() },
                onStrokeCancel = { vm.onStrokeCancel() },
                onFillTap = { offset, size -> vm.onFillTap(offset, size) },
                pickingCloneSource = uiState.activeTool == Tool.CLONE && uiState.cloneSource == null,
                onPickCloneSource = { at -> vm.onSetCloneSource(at) },
                onEyedropStart = { size -> vm.onEyedropStart(size) },
                onEyedropSample = { offset -> vm.onEyedropSample(offset) },
                onEyedropEnd = { commit -> vm.onEyedropEnd(commit) },
            )
        }

        // 3a-bis. Lasso capture layer — traces a new selection, or moves the pixels of the current
        // one when the drag starts inside it. Screen space, like the brush layer, so the polygon it
        // records maps into any layer through the same screen→bitmap transform the paint uses.
        if (uiState.activeTool == Tool.SELECT) {
            SelectionCanvas(
                selection = uiState.selection,
                shape = uiState.selectionShape,
                op = uiState.selectionOp,
                gate = strokeGate,
                modifier = Modifier.fillMaxSize(),
                onSelectionEnd = { pts, size -> vm.onSelectionEnd(pts, size) },
                onSelectionMove = { delta -> vm.onSelectionMove(delta) },
                onAutoSelect = { at, size -> vm.onAutoSelect(at, size) },
                onClearSelection = { vm.onClearSelection() },
            )
        }

        // 3a-ter. Marching ants for the committed selection. Purely visual, and shown under every
        // tool — the selection keeps clipping the brush after you switch back to it, so hiding the
        // outline would leave strokes mysteriously stopping at an invisible edge.
        uiState.selection?.let { sel ->
            SelectionMarquee(selection = sel, modifier = Modifier.fillMaxSize())
        }

        // 3a-quater. Clone's source reticle. Shown only while the tool is active, because it marks
        // where the *next* stroke will copy from — with any other tool it would be a crosshair on
        // the artwork meaning nothing. Without it the tool is aimed at a point the user has to
        // remember, and a clone brush sampling from somewhere invisible is indistinguishable from
        // one that is broken.
        if (uiState.activeTool == Tool.CLONE) {
            uiState.cloneSource?.let { src ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = 14.dp.toPx()
                    // Black under white, like the marching ants, so it reads on any artwork.
                    listOf(Color.Black.copy(alpha = 0.6f) to 3f, Color.White to 1.5f).forEach { (c, w) ->
                        drawCircle(color = c, radius = r, center = src, style = Stroke(width = w))
                        drawLine(c, Offset(src.x - r, src.y), Offset(src.x + r, src.y), strokeWidth = w)
                        drawLine(c, Offset(src.x, src.y - r), Offset(src.x, src.y + r), strokeWidth = w)
                    }
                }
            }
        }

        // 3a-quinquies. Distort/Warp handles. Above the artwork and above the paint surfaces, so
        // that while a bending transform is open the grid owns the touches — dragging a handle must
        // never also lay down a stroke.
        if (uiState.transformMode != TransformMode.FREEFORM && uiState.warpHandles.isNotEmpty()) {
            WarpHandles(
                handles = uiState.warpHandles,
                gridSize = uiState.transformMode.gridSize,
                onHandleMoved = { i, at -> vm.onWarpHandleMoved(i, at) },
                onRelease = { vm.onWarpHandleReleased() },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 3b. Pen (vector) capture layer — a freeform drag traces a live poly-line that is committed
        // as an open PATH vector layer on release. Needs no active layer (it makes a new one). Runs
        // in screen space; onCommitPenPath maps the points back through the camera.
        if (uiState.activeTool == Tool.PEN) {
            PenCanvas(
                color = uiState.activeColor,
                strokeWidthPx = uiState.brushSize,
                gate = strokeGate,
                onCommit = { pts, cw, ch -> vm.onCommitPenPath(pts, cw, ch) },
                onCommitEllipse = { c, rx, ry, cw, ch -> vm.onCommitPenEllipse(c, rx, ry, cw, ch) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 3b-bis. Vector node editor — anchors and bezier handles for the path layer being edited.
        // Above the transform/gesture layers so a drag on a node is claimed here rather than moving
        // the whole layer, and only mounted while node editing is actually on.
        uiState.pathEditLayerId?.let { editId ->
            uiState.layers.firstOrNull { it.id == editId }?.let { pathLayer ->
                PathEditOverlay(
                    layer = pathLayer,
                    uiState = uiState,
                    onEditStart = { vm.onPathEditStart() },
                    onMoveNode = { i, dx, dy -> vm.onMovePathNode(i, dx, dy) },
                    onMoveHandle = { i, out, x, y -> vm.onMovePathHandle(i, out, x, y, mirror = true) },
                    onEditEnd = { vm.onPathEditEnd() },
                    onSelectNode = { vm.onSelectPathNode(it) },
                    onInsertNode = { seg, t -> vm.onInsertPathNode(seg, t) },
                    onToggleCornerSmooth = { index ->
                        val node = pathLayer.shapes.singleOrNull()
                            ?.let { PathEditing.nodes(it).getOrNull(index) }
                        if (node?.isCorner == false) vm.onMakePathNodeCorner(index) else vm.onMakePathNodeSmooth(index)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 3c. Eyedropper loupe — Procreate's touch-and-hold sampler. A ring above the finger filled
        // with the colour currently under it; screen-space overlay, purely visual.
        if (uiState.isEyedropping) {
            EyedropLoupe(
                color = uiState.eyedropColor,
                position = uiState.eyedropPosition,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 3d. Brush-size HUD — while the size slider moves, the ACTUAL brush diameter previews as
        // a circle at canvas centre, the way Procreate shows what you're about to paint with.
        // The radial gradient maps brushFeathering so the user sees the soft edge, not just size.
        if (uiState.brushHudVisible) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val r = (uiState.brushSize / 2f).coerceAtLeast(1.5f)
                val core = (1f - uiState.brushFeathering).coerceIn(0f, 1f)
                val c = uiState.activeColor
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to c,
                        core to c,
                        1f to c.copy(alpha = 0f),
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                    center = center,
                )
                drawCircle(Color.White.copy(alpha = 0.9f), radius = r, center = center, style = Stroke(width = 1.5.dp.toPx()))
            }
        }

        // 3e. HUD pill — Procreate's transient confirmation ("Undo", "Redo") at the top of the canvas.
        uiState.hudMessage?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 6.dp),
            ) {
                Text(message, color = Color.White)
            }
        }

        // 3f. QuickMenu — Procreate's radial six-slot, opened by holding four fingers and centred
        // where they landed. Drawn above the canvas overlays and below the panels: it is modal over
        // the artwork (its scrim swallows touches) but should never cover a panel it just opened.
        uiState.quickMenuAt?.let { at ->
            val activeId = uiState.activeLayerId
            QuickMenu(
                center = at,
                onDismiss = { vm.onDismissQuickMenu() },
                modifier = Modifier.fillMaxSize(),
                actions = listOf(
                    QuickAction("Undo", enabled = uiState.undoCount > 0) { vm.onUndoClicked() },
                    QuickAction("Layer") { vm.onAddBlankLayer() },
                    // Enabled on the same terms as everywhere else. It used to ask only whether a
                    // layer was active, so on a group or a vector layer this wedge took the tap and
                    // set a flag nothing reads.
                    QuickAction(
                        "Alpha",
                        enabled = uiState.layers.firstOrNull { it.id == activeId }?.supportsAlphaLock == true,
                    ) {
                        activeId?.let { vm.onToggleAlphaLock(it) }
                    },
                    QuickAction(if (uiState.symmetryMode != SymmetryMode.NONE) "Sym ✓" else "Sym") { vm.onToggleSymmetry() },
                    QuickAction("Deselect", enabled = uiState.selection != null) { vm.onClearSelection() },
                    QuickAction("Redo", enabled = uiState.redoCount > 0) { vm.onRedoClicked() },
                ),
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
                    // There is no "File" menu — the top-right dropdown is a flat Open/New/Import
                    // list — and it isn't the mechanism anyway: picking a raster tool creates a
                    // layer on demand (EditorViewModel.setActiveTool). That's the fastest path for
                    // a genuinely new document; the menu still covers bringing in an existing one.
                    text = "Pick a tool from the rail to start a layer,\nor use the menu to open or import one.",
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
            imperial = uiState.isImperialUnits,
        )

        // 5. Loading indicator.
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun LayerStackNode(
    node: LayerNode,
    uiState: EditorUiState,
    modifier: Modifier = Modifier,
    // Animation Assist's per-frame opacity override, applied on top of the layer's own opacity.
    // Only ever non-1 at the top level (a frame), because a GROUP frame's graphicsLayer alpha
    // already covers its whole subtree — recursive calls take the 1f default so it isn't squared.
    frameAlpha: Float = 1f,
) {
    val layer = node.layer
    if (!layer.isVisible) return
    if (frameAlpha <= 0f) return

    key(layer.id) {
        if (layer.type == com.hereliesaz.graffitixr.common.model.LayerType.GROUP) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = layer.offset.x
                        translationY = layer.offset.y
                        scaleX = layer.scale
                        scaleY = layer.scale
                        rotationX = layer.rotationX
                        rotationY = layer.rotationY
                        rotationZ = layer.rotationZ
                        alpha = layer.opacity * frameAlpha
                        transformOrigin = TransformOrigin.Center
                        blendMode = layer.blendMode
                        compositingStrategy = if (layer.blendMode != BlendMode.SrcOver)
                            CompositingStrategy.Offscreen
                        else
                            CompositingStrategy.Auto
                    }
            ) {
                node.children.forEach { child ->
                    LayerStackNode(child, uiState)
                }
            }
        } else {
            // Vector layer: drawn from its shapes via Canvas. (A raster layer's bitmap path below
            // no-ops for it, since a vector layer carries no bitmap.)
            if (layer.shapes.isNotEmpty()) {
                VectorLayerContent(layer, modifier = Modifier.fillMaxSize(), frameAlpha = frameAlpha)
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
                // Offscreen compositing is only needed to isolate a non-default blend mode (or a
                // clip); for a normal (SrcOver, unclipped) layer, Auto avoids the extra full-screen
                // pass per frame. Clip-to-below (Procreate's Clipping Mask) masks against whatever
                // this Box has already drawn beneath it via BlendMode.SrcAtop — see ExportManager's
                // matching PorterDuff.Mode.SRC_ATOP for why it takes priority over a custom blend
                // mode rather than combining both.
                //
                // SrcAtop, NOT SrcIn. SrcIn keeps the destination only where the source covers it,
                // so turning Clip to Below on erased the layer underneath everywhere the clipped
                // layer happened not to paint — the exact inverse of a clipping mask, which shows
                // the upper layer only inside the lower one's alpha and leaves the lower one alone
                // everywhere else. That is SrcAtop.
                val needsOffscreen = layer.blendMode != BlendMode.SrcOver || layer.clipToLayerBelow
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
                            alpha = layer.opacity * frameAlpha
                            transformOrigin = TransformOrigin.Center
                            blendMode = if (layer.clipToLayerBelow) BlendMode.SrcAtop else layer.blendMode
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
}

/**
 * Procreate's canvas navigation: **two or more fingers pan, pinch-zoom and rotate the workspace**,
 * about the pinch centroid — under every tool, with anything or nothing selected. It is the one
 * gesture that must never be modal, because there is no other way to move the canvas while you are
 * painting on it.
 *
 * A single finger is left strictly alone: no camera drag, no consumption, nothing. That finger
 * belongs to whichever tool is active (or to nobody, in Transform mode over empty space), and this
 * layer must not so much as touch it. Only from the second finger onwards does it claim the gesture
 * and consume, which is also the point at which the drawing surfaces have abandoned theirs — they
 * treat a second finger as "this is a gesture, not painting" and drop out of their loops.
 *
 * [awaitFirstDown] takes `requireUnconsumed = false` because the tool surfaces above legitimately
 * claim the first down (the Transform layer's tap detector consumes it outright); waiting for an
 * unconsumed one would mean never starting to track, and the pinch that follows would be missed.
 */
/**
 * A **[Modifier], applied to an ancestor** — not a sibling layer, which is what this used to be and
 * why canvas navigation did not work at all.
 *
 * Compose hit-tests a Box's children from the top down and stops at the first one that is hit.
 * Overlapping siblings do not all receive pointer events; only the topmost does. So a full-size
 * navigation layer sitting *underneath* the drawing surface received nothing whatsoever — not
 * "consumed events it should ignore", nothing — for as long as any tool surface was mounted above
 * it, which is whenever a tool is selected. The old code's comment reasoned carefully about
 * consumption and z-order and was wrong about the only thing that mattered: whether the events
 * arrived. `CanvasNavigationTest` demonstrates it directly — an inert sibling on top that consumes
 * nothing at all still starves the layer below.
 *
 * Ancestors have no such problem. The pointer path runs root → leaf, so every ancestor of the node
 * that was hit is in it, and on the Main pass ancestors are dispatched *after* their descendants.
 * That gives exactly the arbitration this gesture wants for free: the tool surface sees each event
 * first and consumes it while it is painting, drops out the moment a second finger lands, and the
 * unconsumed two-finger events then arrive here.
 *
 * The rule itself is Procreate's, and unconditional: **two or more fingers pan, pinch-zoom and
 * rotate the workspace, under every tool, with anything or nothing selected.** A single finger is
 * left strictly alone — no camera drag, no consumption — because it belongs to whichever tool is
 * active, and there is no other way to move the canvas while painting on it.
 */
internal fun Modifier.canvasNavigation(
    onPanZoom: (pan: Offset, zoom: Float, centroid: Offset, rotation: Float) -> Unit,
): Modifier = this.pointerInput(Unit) {
    // Keyed on Unit so it never restarts: keying on the viewport would cancel and relaunch this
    // block on every frame of a pan, dropping the in-flight gesture and making navigation feel dead.
    // The camera state it needs comes from the events themselves, which are deltas.
    awaitEachGesture {
        // requireUnconsumed = false because the tool surfaces below legitimately claim the first
        // down (the Transform layer's tap detector consumes it outright); waiting for an unconsumed
        // one would mean never starting to track, and the pinch that follows would be missed.
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            // One finger is never the camera's — leave it entirely unclaimed.
            if (pressed < 2) continue
            val centroid = event.calculateCentroid()
            if (centroid != Offset.Unspecified) {
                onPanZoom(
                    event.calculatePan(),
                    event.calculateZoom(),
                    centroid,
                    event.calculateRotation(),
                )
            }
            event.changes.forEach { it.consume() }
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
 * Interactive transform handles on the selected layer's bounding box, following the box as it (and
 * the camera) rotate:
 *  • **resize** — the bottom-right corner (`corners[2]`), drawn as a filled square. Dragging it scales
 *    the layer about its centre (distance-from-pivot ratio) via the [onResize] "zoom" path.
 *  • **rotate** — the adjacent top-right corner (`corners[1]`), drawn as a ring. Dragging it spins the
 *    layer about its centre (swept angle) via [onRotate].
 *  • **move** — everywhere else on the frame: the two corners that carry no handle (`corners[0]`
 *    top-left and `corners[3]` bottom-left) and the four edges between them, which drag the layer
 *    around via [onMove]. The box is the thing you pick the layer up by, and until now two of its
 *    corners and all four of its sides were inert: a drag that began on them did nothing at all, so
 *    the frame looked grabbable everywhere and answered in only two places.
 *
 * All three reuse the proven [EditorViewModel.onTransformGesture] lifecycle
 * ([onGestureStart]/[onGestureEnd] bracket history + persistence). A drag that starts on none of
 * them is left unconsumed, so the pan-gesture layer below still handles it — which is what keeps a
 * drag that begins *inside* the artwork the responsibility of that layer rather than this one. The
 * box outline itself is drawn by [SelectionOverlay].
 *
 * Device-tuning note: the handle touch radius, the edge grab width and the render-transform pivot
 * basis (shared with [CanvasHitTest]) are derived, not yet verified on a device.
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
    onMove: (delta: Offset) -> Unit,
    onGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { 24.dp.toPx() }
    // Narrower than a corner handle on purpose: the edges run the whole length of the box, so a
    // 24dp band around all four of them would reach a long way into the artwork and claim drags
    // meant for the layer's interior.
    val edgeGrabPx = with(density) { 14.dp.toPx() }
    val currentLayer by rememberUpdatedState(activeLayer)
    val currentVpOffset by rememberUpdatedState(viewportOffset)
    val currentVpZoom by rememberUpdatedState(viewportZoom)
    val currentVpRotation by rememberUpdatedState(viewportRotation)
    Canvas(
        modifier = modifier.pointerInput(activeLayer.id) {
            awaitEachGesture {
                val corners = CanvasHitTest.layerScreenCorners(
                    currentLayer, size.width.toFloat(), size.height.toFloat(),
                    currentVpOffset, currentVpZoom, currentVpRotation,
                ) ?: return@awaitEachGesture
                val down = awaitFirstDown(requireUnconsumed = true)
                val pivot = CanvasHitTest.boxCenter(corners)
                val resizeHandle = corners[2] // bottom-right
                val rotateHandle = corners[1] // top-right (adjacent)

                val onRotateHandle = (down.position - rotateHandle).getDistance() <= handleRadiusPx
                // Resize only if not already on the rotate handle (they can crowd on a tiny box).
                val onResizeHandle = !onRotateHandle &&
                    (down.position - resizeHandle).getDistance() <= handleRadiusPx
                // The rest of the frame moves the layer: the two corners with no handle on them, and
                // the edges. Checked last so a handle always wins where the two overlap — every
                // corner is also a point on two edges.
                val onMoveGrip = !onRotateHandle && !onResizeHandle && (
                    (down.position - corners[0]).getDistance() <= handleRadiusPx ||
                        (down.position - corners[3]).getDistance() <= handleRadiusPx ||
                        CanvasHitTest.nearBoxEdge(corners, down.position, edgeGrabPx)
                    )
                if (!onRotateHandle && !onResizeHandle && !onMoveGrip) return@awaitEachGesture

                down.consume()
                onGestureStart()
                var prevPos = down.position
                var prevDist = (down.position - pivot).getDistance().coerceAtLeast(1f)
                while (true) {
                    val event = awaitPointerEvent()
                    // A second finger is canvas navigation, never a handle drag — same contract the
                    // drawing surfaces keep. Holding on would consume one of the two pointers and
                    // leave CanvasNavigation with half a pinch.
                    if (event.changes.count { it.pressed } > 1) break
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    when {
                        onRotateHandle ->
                            onRotate(CanvasHitTest.angleDeltaDegrees(pivot, prevPos, change.position))
                        onMoveGrip -> onMove(change.position - prevPos)
                        else -> {
                            val curDist = (change.position - pivot).getDistance().coerceAtLeast(1f)
                            onResize(curDist / prevDist)
                            prevDist = curDist
                        }
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
 * Freeform vector pen capture with **QuickShape**. A single-finger drag traces a poly-line,
 * previewed live in the brush colour; on release the traced screen points are handed to [onCommit]
 * (with the canvas size) to be turned into an open PATH vector layer.
 *
 * QuickShape (Procreate): stop moving and HOLD at the end of the stroke and the wobbly trace snaps
 * to the ideal shape it approximates — the preview switches to the snapped shape while still held,
 * and lifting commits it: a recognized line commits as a clean two-point path, a recognized
 * circle/ellipse goes through [onCommitEllipse] instead.
 *
 * A second finger cancels the trace (it's a gesture, not drawing) and tells [gate] so, so the tap
 * that cancelled it doesn't also fire an undo. Strokes of fewer than two points are ignored.
 */
@Composable
private fun PenCanvas(
    color: Color,
    strokeWidthPx: Float,
    gate: StrokeGate,
    onCommit: (points: List<Offset>, canvasWidth: Float, canvasHeight: Float) -> Unit,
    onCommitEllipse: (center: Offset, radiusX: Float, radiusY: Float, canvasWidth: Float, canvasHeight: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val points = remember { mutableStateListOf<Offset>() }
    var snapped by remember { mutableStateOf<QuickShape.Shape?>(null) }
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                points.clear()
                snapped = null
                val down = awaitFirstDown(requireUnconsumed = false)
                points.add(down.position)
                down.consume()
                var lastMove = down.position
                var cancelled = false
                while (true) {
                    // Timed wait: a finger holding still emits no events, and the timeout is what
                    // triggers QuickShape recognition mid-hold.
                    // AwaitPointerEventScope's own withTimeoutOrNull — the restricted-suspension-safe one.
                    val event = withTimeoutOrNull(QuickShape.HOLD_MS) { awaitPointerEvent() }
                    if (event == null) {
                        if (snapped == null && points.size >= 2) {
                            snapped = QuickShape.recognize(points.toList())
                        }
                        continue
                    }
                    if (event.changes.count { it.pressed } > 1) {
                        cancelled = true
                        // Same contract the raster and lasso surfaces keep: a second finger throws
                        // the trace away, so the gesture that did it must not ALSO undo whatever
                        // came before. Without this, a two-finger tap mid-trace did both.
                        if (points.size >= 2) gate.markCancelled()
                        break
                    }
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    points.add(change.position)
                    if ((change.position - lastMove).getDistance() > QuickShape.HOLD_SLOP_PX) {
                        lastMove = change.position
                        snapped = null // real movement resumes freehand; the hold timer restarts
                    }
                    change.consume()
                }
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                if (!cancelled && points.size >= 2) {
                    when (val shape = snapped) {
                        is QuickShape.Line -> onCommit(listOf(shape.start, shape.end), w, h)
                        is QuickShape.Ellipse -> onCommitEllipse(shape.center, shape.radiusX, shape.radiusY, w, h)
                        null -> onCommit(points.toList(), w, h)
                    }
                }
                points.clear()
                snapped = null
            }
        },
    ) {
        when (val shape = snapped) {
            is QuickShape.Line -> drawLine(color, shape.start, shape.end, strokeWidth = strokeWidthPx)
            is QuickShape.Ellipse -> drawOval(
                color = color,
                topLeft = Offset(shape.center.x - shape.radiusX, shape.center.y - shape.radiusY),
                size = Size(shape.radiusX * 2, shape.radiusY * 2),
                style = Stroke(width = strokeWidthPx),
            )
            null -> if (points.size >= 2) {
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                }
                drawPath(path, color, style = Stroke(width = strokeWidthPx))
            }
        }
    }
}

/**
 * The eyedropper's loupe: a colour-filled ring floated above the sampling finger so the picked
 * colour is visible while the fingertip covers the pixel. Purely visual.
 */
@Composable
private fun EyedropLoupe(color: Color?, position: Offset, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = position - Offset(0f, 56.dp.toPx())
        val radius = 26.dp.toPx()
        drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = radius + 4.dp.toPx(), center = center)
        drawCircle(color = color ?: Color.Transparent, radius = radius, center = center)
        drawCircle(color = Color.White, radius = radius, center = center, style = Stroke(width = 2.dp.toPx()))
        // Crosshair at the actual sample point.
        drawCircle(color = Color.White, radius = 4.dp.toPx(), center = position, style = Stroke(width = 1.5f.dp.toPx()))
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

/** Document px → the chosen unit, assuming the CSS/typical-screen reference of 96 px per inch — the
 *  document model carries no DPI of its own, so this is the same reference every browser ruler uses. */
private const val PX_PER_INCH = 96f
private fun pxToUnit(px: Float, imperial: Boolean): Float = if (imperial) px / PX_PER_INCH else px / PX_PER_INCH * 2.54f

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
    imperial: Boolean,
    spacing: Float = 48f,
) {
    val thickness = with(LocalDensity.current) { 14.dp.toPx() }
    val labelPx = with(LocalDensity.current) { 9.dp.toPx() }
    val labelPaint = remember(labelPx) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(0xB0, 0xB0, 0xB0)
            textSize = labelPx
            isAntiAlias = true
        }
    }
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
                if (x in 0f..w) {
                    drawLine(tickColor, Offset(x, 0f), Offset(x, thickness), strokeWidth = 1f)
                    // Every 5th tick gets a value, so the bar reads as a scale rather than just ticks —
                    // labelling every tick would overlap at any spacing tight enough to be useful.
                    if (k % 5 == 0) {
                        val value = pxToUnit(k * spacing, imperial)
                        drawContext.canvas.nativeCanvas.drawText(
                            "${"%.1f".format(value)}${if (imperial) "\"" else "cm"}",
                            x + 2f, thickness - 3f, labelPaint,
                        )
                    }
                }
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
private fun VectorLayerContent(layer: Layer, modifier: Modifier = Modifier, frameAlpha: Float = 1f) {
    Canvas(
        modifier = modifier.graphicsLayer {
            translationX = layer.offset.x
            translationY = layer.offset.y
            scaleX = layer.scale
            scaleY = layer.scale
            rotationX = layer.rotationX
            rotationY = layer.rotationY
            rotationZ = layer.rotationZ
            alpha = layer.opacity * frameAlpha
            transformOrigin = TransformOrigin.Center
            // SrcAtop, not SrcIn — see the raster path's note above: SrcIn erases the layer below
            // wherever the clipped layer is transparent, which inverts what a clipping mask means.
            blendMode = if (layer.clipToLayerBelow) BlendMode.SrcAtop else layer.blendMode
            if (layer.clipToLayerBelow) compositingStrategy = CompositingStrategy.Offscreen
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
            val path = pathFigure(shape, cx, cy) ?: return
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
 * The figure for a [ShapeKind.PATH], curving through each node's bezier handles where it has them
 * and falling back to straight segments where it doesn't. A path with no handles at all takes the
 * poly-line path above unchanged, so nothing about existing pen strokes or imported paths shifts.
 * ExportManager builds the identical figure with an android.graphics.Path.
 */
private fun pathFigure(shape: VectorShape, cx: Float, cy: Float): Path? {
    if (!shape.hasCurves) return pointsPath(shape.points, cx, cy, shape.closed)
    val nodes = PathEditing.nodes(shape)
    if (nodes.size < 2) return null
    return Path().apply {
        moveTo(cx + nodes[0].x, cy + nodes[0].y)
        for (s in 0 until PathEditing.segmentCount(nodes.size, shape.closed)) {
            val a = nodes[s]
            val b = nodes[(s + 1) % nodes.size]
            if (a.isCorner && b.isCorner) {
                lineTo(cx + b.x, cy + b.y)
            } else {
                cubicTo(
                    cx + a.x + a.outDx, cy + a.y + a.outDy,
                    cx + b.x + b.inDx, cy + b.y + b.inDy,
                    cx + b.x, cy + b.y,
                )
            }
        }
        if (shape.closed) close()
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
