from pathlib import Path
p = Path('app/src/main/java/com/hereliesaz/graffux/MainActivity.kt')
s = p.read_text()
old = '''        val selectionShapeOrder = listOf(
            SelectionShape.RECTANGLE,
            SelectionShape.FREEHAND,
            SelectionShape.ELLIPSE,
            SelectionShape.AUTOMATIC,
        )
        selectionShapeOrder.forEach { mode ->
            val id = "selectShape.${mode.name}"
            azRailItem(
                id = id, classifiers = setOf(id),
                text = mode.label,
                content = when (mode) {
                    SelectionShape.FREEHAND -> GraffuxIcons.SelectLasso
                    SelectionShape.RECTANGLE -> GraffuxIcons.SelectRect
                    SelectionShape.ELLIPSE -> GraffuxIcons.SelectEllipse
                    SelectionShape.AUTOMATIC -> GraffuxIcons.SelectWand
                },
                color = railColor(id),
                shape = AzButtonShape.NONE_SQUARE,
                fillColor = railColor(id),
                onClick = { vm.onSetSelectionShape(mode) },
            )
        }
        nestedTool(Tool.SELECT, "Select", GraffuxIcons.SelectSubject)
'''
new = '''        fun selectionShapeItem(mode: SelectionShape) {
            val id = "selectShape.${mode.name}"
            azRailItem(
                id = id, classifiers = setOf(id),
                text = mode.label,
                content = when (mode) {
                    SelectionShape.FREEHAND -> GraffuxIcons.SelectLasso
                    SelectionShape.RECTANGLE -> GraffuxIcons.SelectRect
                    SelectionShape.ELLIPSE -> GraffuxIcons.SelectEllipse
                    SelectionShape.AUTOMATIC -> GraffuxIcons.SelectWand
                },
                color = railColor(id),
                shape = AzButtonShape.NONE_SQUARE,
                fillColor = railColor(id),
                onClick = { vm.onSetSelectionShape(mode) },
            )
        }
        // Rectangle is the standard marquee and should lead the popup. Everything that used to
        // follow Select retains its original relative order.
        selectionShapeItem(SelectionShape.RECTANGLE)
        nestedTool(Tool.SELECT, "Select", GraffuxIcons.SelectSubject)
        listOf(
            SelectionShape.FREEHAND,
            SelectionShape.ELLIPSE,
            SelectionShape.AUTOMATIC,
        ).forEach(::selectionShapeItem)
'''
if old not in s:
    raise SystemExit('selection block not found')
p.write_text(s.replace(old,new,1))
