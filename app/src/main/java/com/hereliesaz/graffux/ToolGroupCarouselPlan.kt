package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.TransformMode
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import androidx.compose.ui.geometry.Offset

/** Former nested tool rails are pages of the existing carousel; unattached hosts stay in AzNavRail. */
internal val CarouselCategory.isToolGroup: Boolean get() =
    this == CarouselCategory.TRANSFORM || this == CarouselCategory.SELECTION || this == CarouselCategory.VECTOR

/** Non-destructive derivation: each entry retains the same id, icon and editor action as its old rail child. */
internal fun toolGroupCarouselEntries(category: CarouselCategory, s: EditorUiState): List<CarouselEntry> = buildList {
    fun command(id: String, label: String, icon: Int, description: String, selected: Boolean = false) {
        add(CarouselEntry(id, label, CarouselAction.GroupCommand(id), selected, icon, description = description))
    }
    fun tool(t: Tool, label: String, icon: Int, description: String) {
        add(CarouselEntry(TOOL_IDS.getValue(t), label, CarouselAction.PickTool(t), s.activeTool == t,
            icon, description = description))
    }
    when (category) {
        CarouselCategory.TRANSFORM -> {
            command("adj.transform", "Move / Transform", GraffuxIcons.Move,
                "Move and transform the active layer", s.activePanel == EditorPanel.TRANSFORM)
            TransformMode.entries.forEach { mode ->
                command("transform.${mode.name}", mode.label, when (mode) {
                    TransformMode.FREEFORM -> GraffuxIcons.SelectTransform
                    TransformMode.DISTORT -> GraffuxIcons.PerspectiveTransform
                    TransformMode.WARP -> GraffuxIcons.PuppetWarp
                }, "Transform mode", s.transformMode == mode)
            }
            if (s.transformMode != TransformMode.FREEFORM) {
                command("transform.apply", "Apply", GraffuxIcons.Success, "Commit transformation")
                command("transform.cancel", "Cancel", GraffuxIcons.Close, "Discard transformation")
            }
        }
        CarouselCategory.SELECTION -> {
            command("selectShape.RECTANGLE", "Rectangle", GraffuxIcons.SelectRect,
                "Rectangular selection", s.selectionShape == SelectionShape.RECTANGLE)
            tool(Tool.SELECT, "Select", GraffuxIcons.SelectSubject, "Draw and edit selections")
            listOf(SelectionShape.FREEHAND, SelectionShape.ELLIPSE, SelectionShape.AUTOMATIC).forEach { shape ->
                add(CarouselEntry("selectShape.${shape.name}", shape.label,
                    CarouselAction.SelectShape(shape), s.selectionShape == shape,
                    when (shape) {
                        SelectionShape.FREEHAND -> GraffuxIcons.SelectLasso
                        SelectionShape.RECTANGLE -> GraffuxIcons.SelectRect
                        SelectionShape.ELLIPSE -> GraffuxIcons.SelectEllipse
                        SelectionShape.AUTOMATIC -> GraffuxIcons.SelectWand
                    }, description = "Selection shape"))
            }
            SelectionOp.entries.forEach { op ->
                command("selectOp.${op.name}", op.label, when (op) {
                    SelectionOp.NEW -> GraffuxIcons.SelectAll
                    SelectionOp.ADD -> GraffuxIcons.SelectAdd
                    SelectionOp.REMOVE -> GraffuxIcons.SelectSubtract
                }, "How to combine selection regions", s.selectionOp == op)
            }
            command("tool.quick", "Quick", GraffuxIcons.SelectQuick, "Open selection quick menu")
            command("tool.selectInvert", "Invert", GraffuxIcons.SelectInvert, "Invert the selection",
                s.selection?.inverted == true)
            command("tool.deselect", "Deselect", GraffuxIcons.SelectNone, "Clear the selection")
            if (s.selection != null) {
                command("sel.fill", "Colour Fill", GraffuxIcons.LayerFill, "Fill the selection")
                command("sel.copy", "Copy", GraffuxIcons.LayerDuplicate, "Copy selected pixels")
                command("sel.cut", "Cut", GraffuxIcons.PathKnife, "Cut selected pixels")
                command("sel.save", "Save Selection", GraffuxIcons.DocumentSave, "Save this selection")
            }
            if (s.hasClipboard) command("sel.paste", "Paste", GraffuxIcons.LayerAdd, "Paste selection")
            s.savedSelections.forEach { saved ->
                command("sel.load.${saved.name}", saved.name, GraffuxIcons.SelectReselect, "Recall selection")
                command("sel.forget.${saved.name}", "Forget ${saved.name}", GraffuxIcons.SelectNone,
                    "Delete saved selection (asks for confirmation)")
            }
        }
        CarouselCategory.VECTOR -> {
            tool(Tool.PEN, "Pen", GraffuxIcons.PenInk, "Draw editable vector paths")
            val editable = s.layers.firstOrNull { it.id == s.activeLayerId }
                ?.shapes?.singleOrNull()?.kind == ShapeKind.PATH
            if (editable || s.pathEditLayerId != null)
                command("tool.nodeEdit", "Edit Nodes", GraffuxIcons.PathSelectDirect, "Edit path nodes",
                    s.pathEditLayerId != null)
            if (s.pathEditLayerId != null) {
                command("tool.nodeClose", "Close Path", GraffuxIcons.PathClose, "Close or open path")
                if (s.selectedNodeIndex != null)
                    command("tool.nodeDelete", "Delete Node", GraffuxIcons.PenRemove, "Delete selected path node")
            }
            tool(Tool.HEAL, "Heal", GraffuxIcons.Heal, "Heal image details")
            tool(Tool.CLONE, "Clone", GraffuxIcons.Stamp, "Clone image pixels")
            if (s.activeTool == Tool.CLONE && s.cloneSource != null)
                command("tool.cloneSource", "Re-aim", GraffuxIcons.FilterCloneSource, "Pick a new clone source")
            tool(Tool.BLUR, "Blur", GraffuxIcons.Blur, "Soften painted areas")
            tool(Tool.SHARPEN, "Sharpen", GraffuxIcons.Sharpen, "Sharpen painted areas")
            tool(Tool.SMUDGE, "Smudge", GraffuxIcons.Smudge, "Blend pixels with the smudge brush")
            tool(Tool.LIQUIFY, "Liquify", GraffuxIcons.Liquify, "Push and warp pixels")
            tool(Tool.DODGE, "Dodge", GraffuxIcons.Dodge, "Lighten painted areas")
            tool(Tool.BURN, "Burn", GraffuxIcons.Burn, "Darken painted areas")
            tool(Tool.COLOR, "Colorize", GraffuxIcons.ColorDisc, "Apply colour")
        }
        else -> Unit
    }
}

/** The old nested rail callbacks, now dispatched by carousel entries; never run on passive group scroll. */
internal fun executeToolGroupCommand(
    vm: EditorViewModel,
    s: EditorUiState,
    id: String,
    screenCenter: Offset,
    onForgetSelectionRequested: (String) -> Unit,
) {
    when {
        id == "adj.transform" -> vm.onTransformClicked()
        id == "transform.apply" -> vm.onApplyWarp()
        id == "transform.cancel" -> vm.onCancelWarp()
        id.startsWith("transform.") -> TransformMode.entries
            .firstOrNull { it.name == id.removePrefix("transform.") }?.let(vm::onSetTransformMode)
        id.startsWith("selectShape.") -> SelectionShape.entries
            .firstOrNull { it.name == id.removePrefix("selectShape.") }?.let(vm::onSetSelectionShape)
        id.startsWith("selectOp.") -> SelectionOp.entries
            .firstOrNull { it.name == id.removePrefix("selectOp.") }?.let(vm::onSetSelectionOp)
        id == "tool.quick" -> vm.onOpenQuickMenu(screenCenter)
        id == "tool.selectInvert" -> vm.onInvertSelection()
        id == "tool.deselect" -> vm.onClearSelection()
        id == "sel.fill" -> vm.onColorFillSelection()
        id == "sel.copy" -> vm.onCopySelection()
        id == "sel.cut" -> vm.onCutSelection()
        id == "sel.save" -> vm.onSaveSelection(vm.nextSelectionName())
        id == "sel.paste" -> vm.onPasteSelection()
        id.startsWith("sel.load.") -> vm.onLoadSelection(id.removePrefix("sel.load."))
        id.startsWith("sel.forget.") -> onForgetSelectionRequested(id.removePrefix("sel.forget."))
        id == "tool.nodeEdit" -> vm.onToggleActivePathEdit()
        id == "tool.nodeClose" -> vm.onTogglePathClosed()
        id == "tool.nodeDelete" -> s.selectedNodeIndex?.let(vm::onDeletePathNode)
        id == "tool.cloneSource" -> vm.onResetCloneSource()
    }
}
