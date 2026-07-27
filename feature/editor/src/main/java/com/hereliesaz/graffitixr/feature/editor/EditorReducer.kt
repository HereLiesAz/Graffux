package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.SymmetryMode
import com.hereliesaz.graffitixr.common.model.Tool

/**
 * The pure state-transition function for the editor — the heart of its MVI design. Given the
 * current [EditorUiState] and an [EditorIntent], it returns the next state with no dependency on
 * Android, Compose, OpenCV, IO, or coroutines, which makes every transition unit-testable without
 * a single mock.
 *
 * Side effects that an intent also triggers (undo-history snapshot, persistence, co-op op
 * emission, OpenCV rasterization) live in EditorViewModel around the dispatch — keeping them out
 * of here is precisely what lets this be pure.
 */
internal object EditorReducer {

    fun reduce(state: EditorUiState, intent: EditorIntent): EditorUiState = when (intent) {
        is EditorIntent.SetOpacity -> state.mapActive { it.copy(opacity = intent.value) }
        is EditorIntent.SetBrightness -> state.mapActive { it.copy(brightness = intent.value) }
        is EditorIntent.SetContrast -> state.mapActive { it.copy(contrast = intent.value) }
        is EditorIntent.SetSaturation -> state.mapActive { it.copy(saturation = intent.value) }
        is EditorIntent.SetColorBalanceR -> state.mapActive { it.copy(colorBalanceR = intent.value) }
        is EditorIntent.SetColorBalanceG -> state.mapActive { it.copy(colorBalanceG = intent.value) }
        is EditorIntent.SetColorBalanceB -> state.mapActive { it.copy(colorBalanceB = intent.value) }
        is EditorIntent.SetScale -> state.mapActive { it.copy(scale = intent.value) }
        is EditorIntent.AddOffset -> state.mapActive { it.copy(offset = it.offset + intent.delta) }
        is EditorIntent.SetRotationX -> state.mapActive { it.copy(rotationX = intent.value) }.copy(activeRotationAxis = RotationAxis.X)
        is EditorIntent.SetRotationY -> state.mapActive { it.copy(rotationY = intent.value) }.copy(activeRotationAxis = RotationAxis.Y)
        is EditorIntent.SetRotationZ -> state.mapActive { it.copy(rotationZ = intent.value) }.copy(activeRotationAxis = RotationAxis.Z)
        is EditorIntent.SetLayerTransform -> state.mapActive {
            it.copy(scale = intent.scale, offset = intent.offset, rotationX = intent.rx, rotationY = intent.ry, rotationZ = intent.rz)
        }
        EditorIntent.ToggleInvert -> state.mapActive { it.copy(isInverted = !it.isInverted) }
        EditorIntent.ToggleImageLock -> state.mapActive { it.copy(isImageLocked = !it.isImageLocked) }
        is EditorIntent.ToggleAlphaLock ->
            state.copy(layers = LayerListOps.mapLayer(state.layers, intent.id) { it.copy(alphaLock = !it.alphaLock) })
        EditorIntent.CycleRotationAxis -> {
            val next = when (state.activeRotationAxis) {
                RotationAxis.X -> RotationAxis.Y
                RotationAxis.Y -> RotationAxis.Z
                RotationAxis.Z -> RotationAxis.X
            }
            state.copy(activeRotationAxis = next, showRotationAxisFeedback = true)
        }

        is EditorIntent.ReorderLayers -> state.copy(layers = LayerListOps.reorderSubset(state.layers, intent.order))
        is EditorIntent.RenameLayer -> state.copy(layers = LayerListOps.rename(state.layers, intent.id, intent.name))
        is EditorIntent.ToggleVisibility -> state.copy(layers = LayerListOps.toggleVisibility(state.layers, intent.id))
        is EditorIntent.GroupLayers -> state.copy(
            layers = LayerListOps.group(state.layers, intent.aId, intent.bId, intent.newGroupId, intent.groupName),
        )
        is EditorIntent.UngroupLayer -> state.copy(layers = LayerListOps.ungroup(state.layers, intent.groupId))
        is EditorIntent.ToggleClipToLayerBelow -> state.copy(
            layers = LayerListOps.mapLayer(state.layers, intent.id) { it.copy(clipToLayerBelow = !it.clipToLayerBelow) },
        )
        is EditorIntent.ActivateLayer -> state.copy(activeLayerId = intent.id, activeTool = Tool.NONE)
        is EditorIntent.AddLayer -> state.copy(
            layers = state.layers + intent.layer,
            activeLayerId = intent.layer.id,
            activeTool = Tool.NONE,
            activePanel = if (intent.resetActivePanel) EditorPanel.NONE else state.activePanel,
        )
        is EditorIntent.RemoveLayer -> {
            val remaining = state.layers.filter { it.id != intent.id }
            state.copy(
                layers = remaining,
                activeLayerId = if (state.activeLayerId == intent.id) remaining.firstOrNull()?.id else state.activeLayerId,
                activeTool = Tool.NONE,
            )
        }
        is EditorIntent.ReplaceLayers -> state.copy(layers = intent.layers, activeLayerId = intent.activeId, activeTool = Tool.NONE)

        is EditorIntent.SetActiveTool -> state.copy(activeTool = intent.tool, activePanel = EditorPanel.NONE)
        EditorIntent.ToggleAdjustPanel ->
            state.copy(activePanel = if (state.activePanel == EditorPanel.ADJUST) EditorPanel.NONE else EditorPanel.ADJUST)
        EditorIntent.ToggleTransformPanel ->
            state.copy(activePanel = if (state.activePanel == EditorPanel.TRANSFORM) EditorPanel.NONE else EditorPanel.TRANSFORM)
        EditorIntent.DismissPanel -> state.copy(activePanel = EditorPanel.NONE)
        is EditorIntent.SetGestureInProgress -> state.copy(gestureInProgress = intent.inProgress)

        is EditorIntent.SetLoading -> state.copy(isLoading = intent.loading)
        is EditorIntent.SetBackgroundBitmap -> state.copy(backgroundBitmap = intent.bitmap)

        is EditorIntent.SetCanvasBackground -> state.copy(canvasBackground = intent.color)
        is EditorIntent.SetDocumentSize -> state.copy(
            documentWidth = intent.width.coerceIn(1, 8192),
            documentHeight = intent.height.coerceIn(1, 8192),
        )
        is EditorIntent.SetViewport -> state.copy(
            viewportOffset = intent.offset,
            viewportZoom = intent.zoom.coerceIn(0.1f, 10f),
            viewportRotation = intent.rotation,
        )
        is EditorIntent.SetSnapGuides -> state.copy(snapGuidesX = intent.x, snapGuidesY = intent.y)
        EditorIntent.ToggleHandedness -> state.copy(isRightHanded = !state.isRightHanded)
        is EditorIntent.SetHandedness -> state.copy(isRightHanded = intent.value)
        is EditorIntent.SetImperialUnits -> state.copy(isImperialUnits = intent.value)
        is EditorIntent.SetGestureMapping -> state.copy(gestureMapping = intent.mapping)
        EditorIntent.ToggleDiagOverlay -> state.copy(showDiagOverlay = !state.showDiagOverlay)
        EditorIntent.FeedbackShown -> state.copy(showRotationAxisFeedback = false)
        is EditorIntent.SetBrushSize -> state.copy(brushSize = intent.value.coerceIn(1f, 200f))
        is EditorIntent.SetBrushFeathering -> state.copy(brushFeathering = intent.value.coerceIn(0f, 1f))
        is EditorIntent.SetBrushFlow -> state.copy(brushFlow = intent.value.coerceIn(0f, 1f))
        is EditorIntent.SetStabilizerLevel -> state.copy(stabilizerLevel = intent.level.coerceIn(0, 100))
        // 240 Hz is above any panel's report rate, so it doubles as "unthrottled" without a
        // special case; 0 means the same thing explicitly.
        is EditorIntent.SetInputSampleRateHz -> state.copy(inputSampleRateHz = intent.hz.coerceIn(0, 240))
        // Floored at a quarter: below that the artwork is visibly soft, and the memory saved is
        // already 94% of what any scale can save.
        is EditorIntent.SetCanvasRenderScale -> state.copy(canvasRenderScale = intent.scale.coerceIn(0.25f, 1f))
        EditorIntent.ToggleWrapAroundMode -> state.copy(wrapAroundMode = !state.wrapAroundMode)
        EditorIntent.ToggleSymmetry -> state.copy(
            symmetryMode = if (state.symmetryMode == SymmetryMode.NONE) SymmetryMode.VERTICAL else SymmetryMode.NONE,
        )
        is EditorIntent.SetSymmetryMode -> state.copy(symmetryMode = intent.mode)
        EditorIntent.ToggleTimeLapseRecording -> state.copy(isTimeLapseRecording = !state.isTimeLapseRecording)
        EditorIntent.ToggleAnimationMode -> state.copy(
            isAnimationMode = !state.isAnimationMode,
            activeFrameIndex = if (!state.isAnimationMode) {
                AnimationFrames.frameIndexForLayer(state.layers, state.activeLayerId)
            } else {
                state.activeFrameIndex
            },
        )
        is EditorIntent.SetAnimationPlaying -> state.copy(isAnimationPlaying = intent.playing)
        is EditorIntent.SetActiveFrameIndex -> {
            val index = intent.index.coerceAtLeast(0)
            // Unlike ActivateLayer this deliberately leaves activeTool alone: stepping frames with a
            // brush in hand must not put the brush down.
            val activeId = if (intent.followActiveLayer) {
                AnimationFrames.drawableLayerForFrame(state.layers, index) ?: state.activeLayerId
            } else {
                state.activeLayerId
            }
            state.copy(activeFrameIndex = index, activeLayerId = activeId)
        }
        EditorIntent.ToggleOnionSkin -> state.copy(onionSkinEnabled = !state.onionSkinEnabled)
        is EditorIntent.SetOnionSkinFrameCount -> state.copy(onionSkinFrameCount = intent.count.coerceIn(1, 5))
        is EditorIntent.SetAnimationFrameDurationMs -> state.copy(animationFrameDurationMs = intent.ms.coerceIn(20, 2000))
        is EditorIntent.SetAnimationLoopMode -> state.copy(animationLoopMode = intent.mode)
        is EditorIntent.SetBrushStudioDraft -> state.copy(
            brushStudioDraft = intent.draft?.sanitized(),
            brushStudioEditingId = intent.editingId,
        )
        is EditorIntent.SetPathEditLayer -> state.copy(
            pathEditLayerId = intent.layerId,
            // A node index belongs to the path it indexes; carrying it across would point at a
            // node in a different shape, or none at all.
            selectedNodeIndex = null,
        )
        is EditorIntent.SelectPathNode -> state.copy(selectedNodeIndex = intent.index)
        is EditorIntent.SetPathShape -> state.copy(
            layers = state.layers.map { layer ->
                if (layer.id == intent.layerId) layer.copy(shapes = listOf(intent.shape)) else layer
            },
        )
        is EditorIntent.SetQuickMenu -> state.copy(quickMenuAt = intent.at)
        // A polygon too small to enclose anything is a deselect, not a selection that silently
        // clips every subsequent stroke to nothing.
        is EditorIntent.SetSelection -> state.copy(
            selection = intent.selection?.takeIf { it.isUsable }
        )
        EditorIntent.InvertSelection -> state.copy(
            selection = state.selection?.let { it.copy(inverted = !it.inverted) }
        )
        is EditorIntent.SetEyedrop -> state.copy(
            isEyedropping = intent.active,
            eyedropColor = if (intent.active) intent.color else null,
            eyedropPosition = intent.position,
        )
        is EditorIntent.SetActiveBrush -> state.copy(activeBrushName = intent.name)
        EditorIntent.ShowColorPicker -> state.copy(showColorPicker = true)
        EditorIntent.DismissColorPicker -> state.copy(showColorPicker = false)
        is EditorIntent.SetActiveColor -> state.copy(activeColor = intent.color, showColorPicker = false)
        is EditorIntent.SetLayerWarp -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.layerId) { it.copy(warpMesh = intent.mesh) })
        is EditorIntent.SetLayerShapes -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.layerId) { it.copy(shapes = intent.shapes) })
        is EditorIntent.SetBlendMode -> state.mapActive { it.copy(blendMode = intent.mode.toComposeBlendMode()) }
        is EditorIntent.RenderTextLayer -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.layerId) { it.copy(bitmap = intent.bitmap, textParams = intent.params) })

        is EditorIntent.AppendLayer -> state.copy(layers = state.layers + intent.layer)
        is EditorIntent.RemoveLayerById -> state.copy(layers = state.layers.filterNot { it.id == intent.id })
        is EditorIntent.SetLayerTransformById -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.id) {
            it.copy(scale = intent.scale, offset = intent.offset, rotationX = intent.rx, rotationY = intent.ry, rotationZ = intent.rz)
        })
        is EditorIntent.SetLayerProps -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.id) {
            it.copy(
                isVisible = intent.props.isVisible,
                opacity = intent.props.opacity,
                brightness = intent.props.brightness,
                contrast = intent.props.contrast,
                saturation = intent.props.saturation,
                colorBalanceR = intent.props.colorBalanceR,
                colorBalanceG = intent.props.colorBalanceG,
                colorBalanceB = intent.props.colorBalanceB,
                isImageLocked = intent.props.isImageLocked,
                isInverted = intent.props.isInverted,
                blendMode = intent.props.blendMode,
            )
        })

        EditorIntent.ToggleColorPanel ->
            state.copy(activePanel = if (state.activePanel == EditorPanel.COLOR) EditorPanel.NONE else EditorPanel.COLOR)
        EditorIntent.ToggleExtensionsPanel ->
            state.copy(activePanel = if (state.activePanel == EditorPanel.EXTENSIONS) EditorPanel.NONE else EditorPanel.EXTENSIONS)
        EditorIntent.BeginGesture -> state.copy(gestureInProgress = true, activePanel = EditorPanel.NONE)
        is EditorIntent.SetLayers -> state.copy(
            layers = intent.layers,
            // Reconcile activeLayerId against the new list: undo/redo can swap in a layer set
            // that no longer contains the previously-active id (e.g. undoing an AddLayer, or
            // redoing a RemoveLayer), which otherwise leaves activeLayerId dangling — the
            // Transform panel, selection outline, and every adjustment control key off
            // `layers.find { it.id == activeLayerId }` and silently no-op once that lookup
            // misses. Mirrors RemoveLayer's fallback below.
            activeLayerId = if (intent.layers.any { it.id == state.activeLayerId }) {
                state.activeLayerId
            } else {
                intent.layers.firstOrNull()?.id
            },
        )
        is EditorIntent.PasteLayerModifications -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.id) {
            it.copy(
                opacity = intent.source.opacity,
                brightness = intent.source.brightness,
                contrast = intent.source.contrast,
                saturation = intent.source.saturation,
                colorBalanceR = intent.source.colorBalanceR,
                colorBalanceG = intent.source.colorBalanceG,
                colorBalanceB = intent.source.colorBalanceB,
                blendMode = intent.source.blendMode,
                warpMesh = intent.source.warpMesh,
            )
        })
        is EditorIntent.LoadedProject -> state.copy(
            projectId = intent.projectId,
            layers = intent.layers,
            activeTool = Tool.NONE,
            // Activate a layer on load, for the same reason SetLayers does above. LoadedProject used
            // to leave activeLayerId null and rely on the SetLayers that follows it — but the view
            // model only dispatches that when some layer still needs its bitmap read off disk. A
            // project made of vector layers (pen paths, shapes) has no bitmap and no uri, so nothing
            // reconciled the id and reopening such a project left every layer-scoped control inert:
            // no selection outline, no Transform, no Adjust, and the Edit rail item hidden outright.
            activeLayerId = if (intent.layers.any { it.id == state.activeLayerId }) {
                state.activeLayerId
            } else {
                intent.layers.firstOrNull()?.id
            },
        )
        EditorIntent.ClearProject -> state.copy(projectId = null, layers = emptyList(), backgroundBitmap = null, activeTool = Tool.NONE)
    }

    /** Applies [transform] to the active layer (no-op when there is no active layer). */
    private fun EditorUiState.mapActive(transform: (Layer) -> Layer): EditorUiState {
        val id = activeLayerId ?: return this
        return copy(layers = LayerListOps.mapLayer(layers, id, transform))
    }
}
