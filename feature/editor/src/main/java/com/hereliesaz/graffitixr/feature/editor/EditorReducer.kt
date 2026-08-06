package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.ComponentOps
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayoutOps
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.StyleOps
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
            activeTool = intent.activeToolOverride ?: Tool.NONE,
            activePanel = if (intent.resetActivePanel) EditorPanel.NONE else state.activePanel,
        )
        is EditorIntent.RemoveLayer -> {
            val ungrouped = LayerListOps.ungroup(state.layers, intent.id)
            val remaining = ungrouped.filter { it.id != intent.id }
            state.copy(
                layers = remaining,
                activeLayerId = if (state.activeLayerId == intent.id) remaining.firstOrNull()?.id else state.activeLayerId,
                activeTool = Tool.NONE,
            )
        }
        is EditorIntent.ReplaceLayers -> state.copy(layers = intent.layers, activeLayerId = intent.activeId, activeTool = Tool.NONE)

        EditorIntent.ToggleLayersPanel ->
            state.copy(activePanel = if (state.activePanel == EditorPanel.LAYERS) EditorPanel.NONE else EditorPanel.LAYERS)

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
        // Turning symmetry back on restores the mode you were using, rather than resetting to
        // Vertical. The toggle used to be destructive against the picker beside it: choose Radial 6,
        // tap off, tap on, and you were silently on Vertical.
        EditorIntent.ToggleSymmetry -> state.copy(
            symmetryMode = if (state.symmetryMode == SymmetryMode.NONE) state.lastSymmetryMode
            else SymmetryMode.NONE,
        )
        is EditorIntent.SetSymmetryMode -> state.copy(
            symmetryMode = intent.mode,
            // Remembered here rather than in the toggle, so picking a mode and then toggling twice
            // comes back to the mode you picked.
            lastSymmetryMode = if (intent.mode == SymmetryMode.NONE) state.lastSymmetryMode else intent.mode,
        )
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
            // Synced here rather than by the caller: editing a path IS a content edit, so if the
            // edited layer is a main component its instances have to follow in the same transition.
            layers = ComponentOps.syncInstances(
                state.layers.map { layer ->
                    if (layer.id == intent.layerId) {
                        val current = layer.shapes
                        val updated = if (current.any { it.kind == intent.shape.kind }) {
                            current.map { if (it.kind == intent.shape.kind) intent.shape else it }
                        } else {
                            current + intent.shape
                        }
                        layer.copy(shapes = updated)
                    } else layer
                },
            ),
        )
        is EditorIntent.MakeComponent ->
            state.copy(layers = ComponentOps.makeComponent(state.layers, intent.layerId, intent.componentId))
        is EditorIntent.PlaceInstance -> state.copy(
            layers = state.layers + intent.instance,
            activeLayerId = intent.instance.id,
        )
        is EditorIntent.DetachInstance ->
            state.copy(layers = ComponentOps.detachInstance(state.layers, intent.instanceId))
        is EditorIntent.ReleaseComponent ->
            state.copy(layers = ComponentOps.releaseComponent(state.layers, intent.componentId))
        EditorIntent.SyncComponents -> state.copy(layers = ComponentOps.syncInstances(state.layers))

        // Layout. Setting either one immediately re-lays the frame out, so the canvas shows the
        // result of the change rather than waiting for the next resize to apply it.
        is EditorIntent.SetLayerConstraints -> state.copy(
            layers = LayerListOps.mapLayer(state.layers, intent.layerId) { it.copy(constraints = intent.constraints) },
        )
        is EditorIntent.SetAutoLayout -> state
            .copy(layers = LayerListOps.mapLayer(state.layers, intent.frameId) { it.copy(autoLayout = intent.layout) })
            .relaidOut(intent.frameId)
        is EditorIntent.RelayoutFrame -> state.relaidOut(intent.frameId)

        // Shared styles. Every branch re-resolves, so a token edit reaches the artwork in the same
        // transition — the artwork stores ids, and resolve() writes the current values into the
        // concrete colour/typography fields the renderers already read.
        is EditorIntent.AddColorStyle -> state.restyled(colorStyles = state.colorStyles + intent.style)
        is EditorIntent.UpdateColorStyle -> state.restyled(
            colorStyles = state.colorStyles.map { if (it.id == intent.style.id) intent.style else it },
        )
        is EditorIntent.DeleteColorStyle -> {
            val (layers, styles) = StyleOps.deleteColorStyle(state.layers, state.colorStyles, intent.styleId)
            state.copy(layers = layers, colorStyles = styles).restyled()
        }
        is EditorIntent.AddTextStyle -> state.restyled(textStyles = state.textStyles + intent.style)
        is EditorIntent.UpdateTextStyle -> state.restyled(
            textStyles = state.textStyles.map { if (it.id == intent.style.id) intent.style else it },
        )
        is EditorIntent.DeleteTextStyle -> {
            val (layers, styles) = StyleOps.deleteTextStyle(state.layers, state.textStyles, intent.styleId)
            state.copy(layers = layers, textStyles = styles).restyled()
        }
        is EditorIntent.SetShapeFillStyle ->
            state.copy(layers = StyleOps.setShapeFillStyle(state.layers, intent.layerId, intent.styleId)).restyled()
        is EditorIntent.SetShapeStrokeStyle ->
            state.copy(layers = StyleOps.setShapeStrokeStyle(state.layers, intent.layerId, intent.styleId)).restyled()
        is EditorIntent.SetLayerTextStyle ->
            state.copy(layers = StyleOps.setTextStyle(state.layers, intent.layerId, intent.styleId)).restyled()
        is EditorIntent.SetQuickMenu -> state.copy(quickMenuAt = intent.at)
        // A polygon too small to enclose anything is a deselect, not a selection that silently
        // clips every subsequent stroke to nothing.
        is EditorIntent.SetSelection -> state.copy(
            selection = intent.selection?.takeIf { it.isUsable }
        )
        EditorIntent.InvertSelection -> state.copy(
            selection = state.selection?.let { it.copy(inverted = !it.inverted) }
        )
        // Deliberately does not clear the current selection: switching modes says what the *next*
        // drag means, and dropping the region under the user's hands would make the mode picker
        // destructive for anyone who tapped it to check which mode they were in.
        is EditorIntent.SetSelectionShape -> state.copy(selectionShape = intent.shape)
        is EditorIntent.SetSelectionOp -> state.copy(selectionOp = intent.op)
        is EditorIntent.SetMagicWandTolerance -> state.copy(magicWandTolerance = intent.tolerance.coerceIn(0, 255))
        // Feather lives on the selection itself, not beside it: it is part of what the region means,
        // so it travels with a moved selection and is recorded into the strokes it clips.
        is EditorIntent.SetCloneSource -> state.copy(cloneSource = intent.at)
        // Changing mode drops the handles: they are a grid of a particular size laid over a
        // particular layer, so carrying a distort's four corners into warp's sixteen would be
        // meaningless. The pixels already baked are kept — only the live grid resets.
        is EditorIntent.SetCanvasSize -> state.copy(canvasSize = intent.size)
        is EditorIntent.SetTransformMode -> state.copy(transformMode = intent.mode, warpHandles = emptyList())
        is EditorIntent.SetWarpHandles -> state.copy(warpHandles = intent.handles)
        is EditorIntent.SetHasClipboard -> state.copy(hasClipboard = intent.has)
        is EditorIntent.SetSavedSelections -> state.copy(savedSelections = intent.selections)
        is EditorIntent.SetSelectionFeather -> state.copy(
            selection = state.selection?.copy(featherPx = intent.featherPx.coerceAtLeast(0f))
        )
        is EditorIntent.SetEyedrop -> state.copy(
            isEyedropping = intent.active,
            eyedropColor = if (intent.active) intent.color else null,
            eyedropPosition = intent.position,
        )
        is EditorIntent.SetActiveBrush -> state.copy(activeBrushName = intent.name)
        EditorIntent.ShowColorPicker -> state.copy(showColorPicker = true)
        EditorIntent.DismissColorPicker -> state.copy(showColorPicker = false)
        is EditorIntent.SetActiveColor -> state.copy(activeColor = intent.color)
        is EditorIntent.SetLayerWarp -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.layerId) { it.copy(warpMesh = intent.mesh) })
        is EditorIntent.SetLayerShapes -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.layerId) { it.copy(shapes = intent.shapes) })
        is EditorIntent.SetBlendMode -> state.mapActive { it.copy(blendMode = intent.mode.toComposeBlendMode()) }
        is EditorIntent.RenderTextLayer -> state.copy(layers = LayerListOps.mapLayer(state.layers, intent.layerId) { it.copy(bitmap = intent.bitmap, textParams = intent.params) })

        is EditorIntent.AppendLayer -> state.copy(layers = state.layers + intent.layer)
        is EditorIntent.RemoveLayerById -> {
            val ungrouped = LayerListOps.ungroup(state.layers, intent.id)
            val remaining = ungrouped.filterNot { it.id == intent.id }
            state.copy(
                layers = remaining,
                activeLayerId = if (state.activeLayerId == intent.id) remaining.firstOrNull()?.id else state.activeLayerId,
            )
        }
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
            // Restore the style registry alongside the artwork, then re-resolve: the saved layers
            // carry only style ids, so without this a reopened document would keep the values it
            // last resolved to but silently stop tracking the tokens.
            colorStyles = intent.colorStyles,
            textStyles = intent.textStyles,
            layers = StyleOps.resolve(intent.layers, intent.colorStyles, intent.textStyles),
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
    /**
     * Re-runs [frameId]'s auto-layout. The frame's rect comes from its own offset and declared
     * layout size — the document is the frame of last resort, so a frame with no declared size
     * falls back to the document bounds rather than laying out into a zero-sized box.
     */
    private fun EditorUiState.relaidOut(frameId: String): EditorUiState {
        val frame = layers.firstOrNull { it.id == frameId } ?: return this
        val w = if (frame.layoutWidth > 0f) frame.layoutWidth else documentWidth.toFloat()
        val h = if (frame.layoutHeight > 0f) frame.layoutHeight else documentHeight.toFloat()
        val rect = com.hereliesaz.graffitixr.common.model.Rect(frame.offset.x, frame.offset.y, w, h)
        return copy(layers = LayoutOps.applyAutoLayout(layers, frameId, rect))
    }

    /**
     * Applies the style registry to the artwork. Called by every style branch so a token edit lands
     * on the layers in the same transition that changed the token — the artwork stores only ids, and
     * this is what turns them back into the concrete values every renderer reads.
     */
    private fun EditorUiState.restyled(
        colorStyles: List<com.hereliesaz.graffitixr.common.model.ColorStyle> = this.colorStyles,
        textStyles: List<com.hereliesaz.graffitixr.common.model.TextStyle> = this.textStyles,
    ): EditorUiState = copy(
        colorStyles = colorStyles,
        textStyles = textStyles,
        layers = StyleOps.resolve(layers, colorStyles, textStyles),
    )

    private fun EditorUiState.mapActive(transform: (Layer) -> Layer): EditorUiState {
        val id = activeLayerId ?: return this
        return copy(layers = LayerListOps.mapLayer(layers, id, transform))
    }
}
