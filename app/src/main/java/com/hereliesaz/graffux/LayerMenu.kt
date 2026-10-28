@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.isContainer
import com.hereliesaz.graffitixr.common.model.ConstraintAnchor
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayoutAlign
import com.hereliesaz.graffitixr.common.model.LayoutDirection
import com.hereliesaz.graffitixr.common.model.supportsAlphaLock
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import kotlin.math.roundToInt

/** What a layer's menu needs from the screen around it. */
internal class LayerMenuEnv(
    val vm: EditorViewModel,
    val strings: AppStrings,
    val onBlendMode: () -> Unit,
    val onEditClicked: () -> Unit,
    val onCurves: () -> Unit,
)

/** A layer's menu: the same rows for a leaf layer and a group. */
internal fun MenuBuilder.layerMenu(layer: Layer, uiState: EditorUiState, env: LayerMenuEnv) {
    val vm = env.vm
    val strings = env.strings
    val onBlendMode = env.onBlendMode
    val onEditClicked = env.onEditClicked
    val onCurves = env.onCurves
    val isGroup = layer.isContainer
    // The layer's own tools, on the layer whose menu you opened. Each one selects that layer
    // first, so a tool never lands on whichever layer happened to be active a moment ago — which
    // is what made these worth moving here rather than leaving them as rail buttons that act on
    // "the active layer, wherever it is". Panels are opened, not drawn: the menu is a list of rows
    // (listItem / inputItem), so the knobs stay in the panels they already live in.
    fun on(action: () -> Unit): () -> Unit = { vm.onLayerActivated(layer.id); action() }

    listItem(strings.nav.adjust, on { vm.onAdjustClicked() })
    listItem("Balance", on { vm.onBalanceClicked() })
    listItem("Blend Mode", on(onBlendMode))
    listItem("Transform", on { vm.onTransformClicked() })
    listItem("Layer Options", on(onEditClicked))

    inputItem(hint = "Rename", initialValue = layer.name) { newName -> vm.onLayerRenamed(layer.id, newName) }
    listItem(if (layer.isVisible) strings.editor.hideLayer else strings.editor.showLayer) { vm.onToggleVisibility(layer.id) }
    if (isGroup) {
        listItem("Ungroup") { vm.onUngroupLayer(layer.id) }
        listItem(strings.editor.delete) { vm.onDeleteGroup(layer.id) }
    } else {
        // The one rule, shared with the quick menu. This used to allow anything that was not a
        // group, so a vector layer offered a lock no stroke would ever consult.
        if (layer.supportsAlphaLock) {
            listItem(if (layer.alphaLock) "Alpha Lock ✓" else "Alpha Lock") { vm.onToggleAlphaLock(layer.id) }
        }
        listItem(if (layer.clipToLayerBelow) "Clip to Below ✓" else "Clip to Below") { vm.onToggleClipToLayerBelow(layer.id) }
        listItem("Curves", on(onCurves))
        listItem(strings.editor.duplicate) { vm.onLayerDuplicated(layer.id) }
        listItem("Merge Down") { vm.onMergeDown(layer.id) }
        listItem("Flatten All") { vm.onFlattenAllLayers() }
        listItem("Group with Above") { vm.onGroupWithLayerAbove(layer.id) }
        listItem("Clear", on { vm.onClearLayer() })
        listItem(strings.editor.delete) { vm.onLayerRemoved(layer.id) }
    }

    // Only a top-level layer can be a frame in the first place (see AnimationFrames' doc
    // comment) -- offered regardless of isGroup, since a GROUP frame is exactly as valid a
    // frame as a plain one, and this only makes sense for a layer that would otherwise BE one.
    if (layer.parentId == null) {
        listItem(if (layer.isPinnedAcrossFrames) "Pinned Across Frames ✓" else "Pin Across Frames") {
            vm.onTogglePinnedAcrossFrames(layer.id)
        }
    }

    // Link to the layer below. The machinery behind this was complete — getLinkedGroupIds walks
    // the contiguous run, and onTransformGesture already moves the whole linked group as one —
    // but onToggleLinkLayer had no caller anywhere, so no layer could ever be linked and the
    // group-move path was unreachable. The README advertised the feature all the same.
    listItem(if (layer.isLinked) "Linked ✓" else "Link to Below") { vm.onToggleLinkLayer(layer.id) }

    // Copy/paste the layer's *look* — opacity, the tone knobs, blend mode and the warp mesh —
    // without touching its pixels. Both halves were implemented and neither had a caller.
    listItem("Copy Adjustments") { vm.copyLayerModifications(layer.id) }
    if (vm.hasCopiedLayerModifications) {
        listItem("Paste Adjustments") { vm.pasteLayerModifications(layer.id) }
    }

    renderLayerStyleMenu(layer, uiState, vm, ::on)
    renderLayerLayoutMenu(layer, uiState, vm, ::on)
    renderLayerComponentMenu(layer, uiState, vm, ::on)
}

/**
 * Shared styles (Figma tokens) for one layer, as menu rows.
 *
 * Every row is gated on the layer actually having something for it to act on, so a raster layer's
 * menu carries none of this. Creating a token also links the layer to it, which is why "New …" only
 * shows where there is a look to lift.
 */
private fun MenuBuilder.renderLayerStyleMenu(
    layer: Layer,
    uiState: EditorUiState,
    vm: EditorViewModel,
    on: (() -> Unit) -> () -> Unit,
) {
    val shape = layer.shapes.firstOrNull()
    val params = layer.textParams
    if (shape != null) {
        listItem("New Colour Style", on {
            vm.onCreateColorStyleFromActive("Colour ${uiState.colorStyles.size + 1}")
        })
    }
    if (params != null) {
        listItem("New Text Style", on {
            vm.onCreateTextStyleFromActive("Text ${uiState.textStyles.size + 1}")
        })
    }
    uiState.colorStyles.forEach { style ->
        val fillLinked = layer.shapes.any { it.fillStyleId == style.id }
        val strokeLinked = layer.shapes.any { it.strokeStyleId == style.id }
        // Update/Delete moved inside this gate with the rows above: they used to sit outside it
        // and ran for every layer regardless of shape != null, so a plain raster layer's menu
        // listed "Update"/"Delete" for every colour style anyone had ever made in the document —
        // exactly what this function's own doc comment says a raster layer's menu carries none of.
        if (shape != null) {
            listItem(if (fillLinked) "Fill: ${style.name} ✓" else "Fill: ${style.name}", on {
                vm.onApplyColorStyle(if (fillLinked) null else style.id)
            })
            // The stroke half of the pair. It had an intent and a StyleOps function and no way in.
            listItem(if (strokeLinked) "Stroke: ${style.name} ✓" else "Stroke: ${style.name}", on {
                vm.onApplyStrokeStyle(if (strokeLinked) null else style.id)
            })
            listItem("Update ${style.name} to Colour", on { vm.onUpdateColorStyleToActiveColor(style.id) })
            listItem("Delete ${style.name}") { vm.onDeleteColorStyle(style.id) }
        }
    }
    uiState.textStyles.forEach { style ->
        // Same fix as the colour loop above: Delete used to run unconditionally.
        if (params != null) {
            val linked = params.styleId == style.id
            listItem(if (linked) "Text: ${style.name} ✓" else "Text: ${style.name}", on {
                vm.onApplyTextStyle(if (linked) null else style.id)
            })
            // The text equivalent of "Update … to Colour", which until now did not exist: a text
            // token could be made and deleted but never edited.
            listItem("Update ${style.name} from Layer", on { vm.onUpdateTextStyleToActive(style.id) })
            listItem("Delete ${style.name}") { vm.onDeleteTextStyle(style.id) }
        }
    }
}

/**
 * Auto-layout and constraints (Figma) for one layer.
 *
 * Auto-layout only where the layer has children to arrange; constraints only where it has a parent
 * to be constrained within — and not even then if that parent runs auto-layout, since the parent
 * owns placement in that case and a constraint would be a control that does nothing.
 */
private fun MenuBuilder.renderLayerLayoutMenu(
    layer: Layer,
    uiState: EditorUiState,
    vm: EditorViewModel,
    on: (() -> Unit) -> () -> Unit,
) {
    val hasChildren = uiState.layers.any { it.parentId == layer.id }
    if (hasChildren && layer.isContainer) {
        // The frame's box, typed as "W x H". Setting it resizes the frame and runs its children's
        // constraints (or its auto-layout) — the numeric twin of the frame's resize handle. Same
        // inputItem row the Rename field above uses; an entry that doesn't parse is ignored.
        val w = if (layer.layoutWidth > 0f) layer.layoutWidth else uiState.documentWidth.toFloat()
        val h = if (layer.layoutHeight > 0f) layer.layoutHeight else uiState.documentHeight.toFloat()
        inputItem(hint = "Frame Size (W x H)", initialValue = "${w.roundToInt()} x ${h.roundToInt()}") { text ->
            val parts = text.split('x', 'X', '×', ',', ' ').mapNotNull { it.trim().toFloatOrNull() }
            if (parts.size == 2) {
                vm.onLayerActivated(layer.id)
                vm.onSetFrameSize(parts[0], parts[1])
            }
        }
    }
    if (hasChildren) {
        val current = layer.autoLayout
        LayoutDirection.entries.forEach { dir ->
            val label = when (dir) {
                LayoutDirection.NONE -> "Free"
                LayoutDirection.HORIZONTAL -> "Row"
                LayoutDirection.VERTICAL -> "Column"
            }
            listItem(if (current.direction == dir) "Layout: $label ✓" else "Layout: $label", on {
                vm.onSetAutoLayout(current.copy(direction = dir))
            })
        }
        if (current.direction != LayoutDirection.NONE) {
            listItem("Hug Contents", on { vm.onHugContents() })
            LayoutAlign.entries.forEach { align ->
                val name = align.name.lowercase().replaceFirstChar { c -> c.uppercase() }
                listItem(if (current.align == align) "Align $name ✓" else "Align $name", on {
                    vm.onSetAutoLayout(current.copy(align = align))
                })
            }
        }
    }
    val parent = layer.parentId?.let { pid -> uiState.layers.firstOrNull { it.id == pid } }
    if (parent != null && parent.autoLayout.direction == LayoutDirection.NONE) {
        ConstraintAnchor.entries.forEach { anchor ->
            val name = anchor.name.lowercase().replaceFirstChar { c -> c.uppercase() }
            listItem(if (layer.constraints.horizontal == anchor) "H: $name ✓" else "H: $name", on {
                vm.onSetConstraints(layer.constraints.copy(horizontal = anchor))
            })
        }
        ConstraintAnchor.entries.forEach { anchor ->
            val name = anchor.name.lowercase().replaceFirstChar { c -> c.uppercase() }
            listItem(if (layer.constraints.vertical == anchor) "V: $name ✓" else "V: $name", on {
                vm.onSetConstraints(layer.constraints.copy(vertical = anchor))
            })
        }
    }
}

/**
 * Components and instances (Figma) for one layer. What is offered follows what the layer already
 * is, so the menu never shows a control that would no-op — Detach only on an instance, Release only
 * on a main, Make Component only on a plain layer.
 */
private fun MenuBuilder.renderLayerComponentMenu(
    layer: Layer,
    uiState: EditorUiState,
    vm: EditorViewModel,
    on: (() -> Unit) -> () -> Unit,
) {
    if (layer.componentId == null && layer.instanceOf == null) {
        listItem("Make Component", on { vm.onMakeComponent() })
    }
    if (layer.instanceOf != null) listItem("Detach Instance", on { vm.onDetachInstance() })
    if (layer.componentId != null) listItem("Release Component", on { vm.onReleaseComponent() })
    uiState.layers.filter { it.componentId != null }.forEach { main ->
        listItem("Place ${main.name}") { main.componentId?.let { vm.onPlaceInstance(it) } }
    }
}

