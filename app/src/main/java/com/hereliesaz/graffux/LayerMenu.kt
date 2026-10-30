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
import com.hereliesaz.graffitixr.design.theme.LayerMenuStrings
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

    val m = strings.layerMenu
    section(m.sectionLayer)
    listItem(strings.nav.adjust, on { vm.onAdjustClicked() })
    listItem(m.balance, on { vm.onBalanceClicked() })
    listItem(m.blendMode, on(onBlendMode))
    if (!isGroup) listItem(m.curves, on(onCurves))
    listItem(m.transform, on { vm.onTransformClicked() })
    listItem(m.layerOptions, on(onEditClicked))
    inputItem(hint = m.rename, initialValue = layer.name) { newName -> vm.onLayerRenamed(layer.id, newName) }
    listItem(if (layer.isVisible) strings.editor.hideLayer else strings.editor.showLayer) { vm.onToggleVisibility(layer.id) }

    // Reordering without the drag (which cannot reach rows scrolled out of the rail, and which
    // TalkBack cannot perform): one place up or down among the layer's siblings.
    section(m.sectionArrange)
    val siblings = layerSiblingsTopFirst(uiState.layers, layer)
    val id = layerRailId(layer.id)
    val up = steppedSiblingOrder(siblings, id, -1)
    val down = steppedSiblingOrder(siblings, id, 1)
    if (up != siblings) listItem(m.moveUp) { vm.onLayerRailRelocated(up, layer.parentId) }
    if (down != siblings) listItem(m.moveDown) { vm.onLayerRailRelocated(down, layer.parentId) }
    if (isGroup) {
        listItem(m.ungroup) { vm.onUngroupLayer(layer.id) }
    } else {
        listItem(strings.editor.duplicate) { vm.onLayerDuplicated(layer.id) }
        listItem(m.groupWithAbove) { vm.onGroupWithLayerAbove(layer.id) }
        listItem(m.mergeDown) { vm.onMergeDown(layer.id) }
    }

    section(m.sectionProperties)
    // The one rule, shared with the quick menu. This used to allow anything that was not a
    // group, so a vector layer offered a lock no stroke would ever consult.
    if (!isGroup && layer.supportsAlphaLock) check(m.alphaLock, layer.alphaLock) { vm.onToggleAlphaLock(layer.id) }
    if (!isGroup) check(m.clipToBelow, layer.clipToLayerBelow) { vm.onToggleClipToLayerBelow(layer.id) }
    // Only a top-level layer can be a frame in the first place (see AnimationFrames' doc
    // comment) -- offered regardless of isGroup, since a GROUP frame is exactly as valid a
    // frame as a plain one, and this only makes sense for a layer that would otherwise BE one.
    if (layer.parentId == null) {
        check(m.showOnEveryFrame, layer.isPinnedAcrossFrames) { vm.onTogglePinnedAcrossFrames(layer.id) }
    }
    // Link to the layer below: a linked run moves as one (getLinkedGroupIds / onTransformGesture).
    check(m.linkToBelow, layer.isLinked) { vm.onToggleLinkLayer(layer.id) }
    // Copy/paste the layer's *look* — opacity, the tone knobs, blend mode and the warp mesh —
    // without touching its pixels.
    listItem(m.copyAdjustments) { vm.copyLayerModifications(layer.id) }
    if (vm.hasCopiedLayerModifications) {
        listItem(m.pasteAdjustments) { vm.pasteLayerModifications(layer.id) }
    }

    renderLayerStyleMenu(layer, uiState, vm, m, ::on)
    renderLayerLayoutMenu(layer, uiState, vm, m, ::on)
    renderLayerComponentMenu(layer, uiState, vm, m, ::on)

    // Destructive actions last, apart from the rest and in the warning colour. Clear and Delete
    // are both undoable; "Flatten All" acts on the whole document, so it lives in the main menu.
    section(m.sectionRemove)
    if (!isGroup) destructiveItem(m.clear, on { vm.onClearLayer() })
    destructiveItem(strings.editor.delete) { if (isGroup) vm.onDeleteGroup(layer.id) else vm.onLayerRemoved(layer.id) }
}

/** [layer]'s siblings (itself included), top of the stack first, as rail ids. */
internal fun layerSiblingsTopFirst(layers: List<Layer>, layer: Layer): List<String> =
    layers.filter { it.parentId == layer.parentId }.reversed().map { layerRailId(it.id) }

/** A constraint's readable name on the [horizontal] or vertical axis ("Left", "Top & bottom"...). */
internal fun constraintLabel(anchor: ConstraintAnchor, horizontal: Boolean, m: LayerMenuStrings): String = when (anchor) {
    ConstraintAnchor.START -> if (horizontal) m.left else m.top
    ConstraintAnchor.END -> if (horizontal) m.right else m.bottom
    ConstraintAnchor.CENTER -> m.centre
    ConstraintAnchor.STRETCH -> if (horizontal) m.leftAndRight else m.topAndBottom
    ConstraintAnchor.SCALE -> m.scale
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
    m: LayerMenuStrings,
    on: (() -> Unit) -> () -> Unit,
) {
    val shape = layer.shapes.firstOrNull()
    val params = layer.textParams
    if (shape == null && params == null) return
    section(m.sectionStyles)
    if (shape != null) {
        listItem(m.newColourStyle, on {
            vm.onCreateColorStyleFromActive("Colour ${uiState.colorStyles.size + 1}")
        })
    }
    if (params != null) {
        listItem(m.newTextStyle, on {
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
            check("${m.fill}: ${style.name}", fillLinked, on {
                vm.onApplyColorStyle(if (fillLinked) null else style.id)
            })
            // The stroke half of the pair. It had an intent and a StyleOps function and no way in.
            check("${m.stroke}: ${style.name}", strokeLinked, on {
                vm.onApplyStrokeStyle(if (strokeLinked) null else style.id)
            })
            listItem(m.updateColourStyle.format(style.name), on { vm.onUpdateColorStyleToActiveColor(style.id) })
            // Undoable (pushHistory in onDeleteColorStyle), still drawn as destructive.
            destructiveItem(m.deleteStyle.format(style.name)) { vm.onDeleteColorStyle(style.id) }
        }
    }
    uiState.textStyles.forEach { style ->
        // Same fix as the colour loop above: Delete used to run unconditionally.
        if (params != null) {
            val linked = params.styleId == style.id
            check("${m.text}: ${style.name}", linked, on {
                vm.onApplyTextStyle(if (linked) null else style.id)
            })
            // The text equivalent of "Update … to Colour", which until now did not exist: a text
            // token could be made and deleted but never edited.
            listItem(m.updateTextStyle.format(style.name), on { vm.onUpdateTextStyleToActive(style.id) })
            destructiveItem(m.deleteStyle.format(style.name)) { vm.onDeleteTextStyle(style.id) }
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
    m: LayerMenuStrings,
    on: (() -> Unit) -> () -> Unit,
) {
    val hasChildren = uiState.layers.any { it.parentId == layer.id }
    val parent = layer.parentId?.let { pid -> uiState.layers.firstOrNull { it.id == pid } }
    val constrained = parent != null && parent.autoLayout.direction == LayoutDirection.NONE
    if (!hasChildren && !constrained) return
    section(m.sectionLayout)
    if (hasChildren && layer.isContainer) {
        // The frame's box, typed as "W x H". Setting it resizes the frame and runs its children's
        // constraints (or its auto-layout) — the numeric twin of the frame's resize handle. Same
        // inputItem row the Rename field above uses; an entry that doesn't parse is ignored.
        val w = if (layer.layoutWidth > 0f) layer.layoutWidth else uiState.documentWidth.toFloat()
        val h = if (layer.layoutHeight > 0f) layer.layoutHeight else uiState.documentHeight.toFloat()
        inputItem(hint = m.frameSize, initialValue = "${w.roundToInt()} x ${h.roundToInt()}") { text ->
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
                LayoutDirection.NONE -> m.layoutFree
                LayoutDirection.HORIZONTAL -> m.layoutRow
                LayoutDirection.VERTICAL -> m.layoutColumn
            }
            check("${m.autoLayout}: $label", current.direction == dir, on {
                vm.onSetAutoLayout(current.copy(direction = dir))
            })
        }
        if (current.direction != LayoutDirection.NONE) {
            listItem(m.fitToContents, on { vm.onHugContents() })
            LayoutAlign.entries.forEach { align ->
                val name = when (align) {
                    LayoutAlign.START -> m.alignStart
                    LayoutAlign.CENTER -> m.centre
                    LayoutAlign.END -> m.alignEnd
                }
                check("${m.align}: $name", current.align == align, on {
                    vm.onSetAutoLayout(current.copy(align = align))
                })
            }
        }
    }
    if (constrained) {
        ConstraintAnchor.entries.forEach { anchor ->
            val name = constraintLabel(anchor, horizontal = true, m)
            check("${m.pinHorizontal}: $name", layer.constraints.horizontal == anchor, on {
                vm.onSetConstraints(layer.constraints.copy(horizontal = anchor))
            })
        }
        ConstraintAnchor.entries.forEach { anchor ->
            val name = constraintLabel(anchor, horizontal = false, m)
            check("${m.pinVertical}: $name", layer.constraints.vertical == anchor, on {
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
    m: LayerMenuStrings,
    on: (() -> Unit) -> () -> Unit,
) {
    section(m.sectionComponent)
    if (layer.componentId == null && layer.instanceOf == null) {
        listItem(m.makeComponent, on { vm.onMakeComponent() })
    }
    if (layer.instanceOf != null) listItem(m.detachInstance, on { vm.onDetachInstance() })
    if (layer.componentId != null) listItem(m.releaseComponent, on { vm.onReleaseComponent() })
    uiState.layers.filter { it.componentId != null }.forEach { main ->
        listItem(m.placeCopy.format(main.name)) { main.componentId?.let { vm.onPlaceInstance(it) } }
    }
}

