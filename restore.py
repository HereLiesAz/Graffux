import sys

filepath = 'app/src/main/java/com/hereliesaz/graffux/MainActivity.kt'
with open(filepath, 'r') as f:
    content = f.read()

target_index = content.find('    val displayMode = if (uiState.symmetryMode != SymmetryMode.NONE) uiState.symmetryMode else uiState.lastSymmetryMode ?: SymmetryMode.VERTICAL')

if target_index == -1:
    print('Target not found')
    sys.exit(1)

code_to_insert = """
    // Design tools (Figma). Wrapped in a single nested rail at the bottom.
    run {
        val active = uiState.layers.firstOrNull { it.id == uiState.activeLayerId }
        val hasChildren = active != null && uiState.layers.any { it.parentId == active.id }
        val hasParent = active?.parentId != null
        val hasShape = active?.shapes?.isNotEmpty() == true
        val hasText = active?.textParams != null
        val components = uiState.layers.filter { it.componentId != null }
        val editable = active?.shapes?.singleOrNull()?.kind == ShapeKind.PATH
        
        val showDesign = (hasChildren || hasParent) || 
                         (uiState.colorStyles.isNotEmpty() || uiState.textStyles.isNotEmpty() || hasShape || hasText) || 
                         (active != null || components.isNotEmpty()) || 
                         (editable || uiState.pathEditLayerId != null)

        if (showDesign) {
            azRailHostItem(
                id = "grp.designTools", text = "Design",
                content = DesignR.drawable.ic_ps_transform, color = navItemColor,
            )

            // Layout
            if (hasChildren || hasParent) {
                if (hasChildren) {
                    val current = active.autoLayout
                    LayoutDirection.entries.forEach { dir ->
                        azRailSubItem(
                            id = "lay.dir.${dir.name}", hostId = "grp.designTools",
                            text = when (dir) {
                                LayoutDirection.NONE -> "Free"
                                LayoutDirection.HORIZONTAL -> "Row"
                                LayoutDirection.VERTICAL -> "Column"
                            },
                            shape = AzButtonShape.NONE, content = DesignR.drawable.ic_ps_transform,
                            color = if (current.direction == dir) activeColor else navItemColor,
                            onClick = { vm.onSetAutoLayout(current.copy(direction = dir)) },
                        )
                    }
                    if (current.direction != LayoutDirection.NONE) {
                        azRailSubItem(
                            id = "lay.hug", hostId = "grp.designTools", text = "Hug Contents",
                            shape = AzButtonShape.NONE, content = DesignR.drawable.ic_ps_fit,
                            color = navItemColor,
                            onClick = { vm.onHugContents() },
                        )
                        azRailSlider(
                            id = "lay.gap", text = "Gap",
                            value = current.gap,
                            config = AzSliderConfig(
                                orientation = AzSliderOrientation.VERTICAL, valueFrom = 0f, valueTo = 100f,
                            ),
                            color = navItemColor,
                            valueFormatter = { "${it.roundToInt()}" },
                            onValueChange = { vm.onSetAutoLayout(current.copy(gap = it)) },
                        )
                        LayoutAlign.entries.forEach { align ->
                            azRailSubItem(
                                id = "lay.align.${align.name}", hostId = "grp.designTools",
                                text = "Align ${align.name.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                                shape = AzButtonShape.NONE, content = DesignR.drawable.ic_ps_transform,
                                color = if (current.align == align) activeColor else navItemColor,
                                onClick = { vm.onSetAutoLayout(current.copy(align = align)) },
                            )
                        }
                    }
                }
                val parentAutoLays = active?.parentId
                    ?.let { pid -> uiState.layers.firstOrNull { it.id == pid } }
                    ?.autoLayout?.direction != LayoutDirection.NONE
                if (hasParent && !parentAutoLays) {
                    ConstraintAnchor.entries.forEach { anchor ->
                        azRailSubItem(
                            id = "lay.h.${anchor.name}", hostId = "grp.designTools",
                            text = "H: ${anchor.name.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                            shape = AzButtonShape.NONE, content = DesignR.drawable.ic_ps_transform,
                            color = if (active.constraints.horizontal == anchor) activeColor else navItemColor,
                            onClick = { vm.onSetConstraints(active.constraints.copy(horizontal = anchor)) },
                        )
                    }
                    ConstraintAnchor.entries.forEach { anchor ->
                        azRailSubItem(
                            id = "lay.v.${anchor.name}", hostId = "grp.designTools",
                            text = "V: ${anchor.name.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                            shape = AzButtonShape.NONE, content = DesignR.drawable.ic_ps_transform,
                            color = if (active.constraints.vertical == anchor) activeColor else navItemColor,
                            onClick = { vm.onSetConstraints(active.constraints.copy(vertical = anchor)) },
                        )
                    }
                }
            }

            // Styles
            if (uiState.colorStyles.isNotEmpty() || uiState.textStyles.isNotEmpty() || hasShape || hasText) {
                if (hasShape) {
                    azRailSubItem(
                        id = "sty.newColor", hostId = "grp.designTools", text = "New Colour Style",
                        shape = AzButtonShape.NONE, content = DesignR.drawable.ic_ps_color,
                        color = navItemColor,
                        onClick = { vm.onCreateColorStyleFromActive("Colour ${uiState.colorStyles.size + 1}") },
                    )
                }
                if (hasText) {
                    azRailSubItem(
                        id = "sty.newText", hostId = "grp.designTools", text = "New Text Style",
                        shape = AzButtonShape.NONE, content = DesignR.drawable.ic_ps_text,
                        color = navItemColor,
                        onClick = { vm.onCreateTextStyleFromActive("Text ${uiState.textStyles.size + 1}") },
                    )
                }
                uiState.colorStyles.forEach { style ->
                    val linked = active?.shapes?.any { it.fillStyleId == style.id } == true
                    azRailSubItem(
                        id = "sty.color.${style.id}", hostId = "grp.designTools", text = style.name,
                        shape = AzButtonShape.NONE, content = DesignR.drawable.ic_ps_color,
                        color = if (linked) activeColor else navItemColor,
                        onClick = { vm.onApplyColorStyle(if (linked) null else style.id) },
                    )
                }
                uiState.textStyles.forEach { style ->
                    val linked = active?.textParams?.styleId == style.id
                    azRailSubItem(
                        id = "sty.text.${style.id}", hostId = "grp.designTools", text = style.name,
                        shape = AzButtonShape.NONE, content = DesignR.drawable.ic_ps_text,
                        color = if (linked) activeColor else navItemColor,
                        onClick = { vm.onApplyTextStyle(if (linked) null else style.id) },
                    )
                }
            }

            // Components
            if (active != null || components.isNotEmpty()) {
                if (active != null && active.componentId == null && active.instanceOf == null) {
                    azRailSubItem(
                        id = "cmp.make", hostId = "grp.designTools", text = "Make Component",
                        shape = AzButtonShape.NONE, content = DesignR.drawable.ic_ps_extension,
                        color = navItemColor,
                        onClick = { vm.onMakeComponent() },
                    )
                }
                if (active?.instanceOf != null) {
                    azRailSubItem(
                        id = "cmp.detach", hostId = "grp.designTools", text = "Detach Instance",
                        shape = AzButtonShape.NONE, content = DesignR.drawable.ic_ps_deselect,
                        color = activeColor,
                        onClick = { vm.onDetachInstance() },
                    )
                }
                if (active?.componentId != null) {
                    azRailSubItem(
                        id = "cmp.release", hostId = "grp.designTools", text = "Release Component",
                        shape = AzButtonShape.NONE, content = DesignR.drawable.ic_ps_deselect,
                        color = activeColor,
                        onClick = { vm.onReleaseComponent() },
                    )
                }
                components.forEach { main ->
                    azRailSubItem(
                        id = "cmp.place.${main.componentId}", hostId = "grp.designTools",
                        text = "Place ${main.name}", shape = AzButtonShape.NONE,
                        content = DesignR.drawable.ic_ps_layers, color = navItemColor,
                        onClick = { main.componentId?.let { vm.onPlaceInstance(it) } },
                    )
                }
            }

            // Vector Nodes
            if (editable || uiState.pathEditLayerId != null) {
                azRailSubItem(
                    id = "tool.nodeEdit",
                    hostId = "grp.designTools",
                    text = if (uiState.pathEditLayerId != null) "Nodes On" else "Edit Nodes",
                    shape = AzButtonShape.NONE,
                    content = TablerIcons.Vector,
                    color = if (uiState.pathEditLayerId != null) activeColor else navItemColor,
                    onClick = { vm.onToggleActivePathEdit() },
                )
            }
            if (uiState.pathEditLayerId != null) {
                azRailSubItem(
                    id = "tool.nodeClose", hostId = "grp.designTools", text = "Close Path",
                    shape = AzButtonShape.NONE,
                    content = TablerIcons.Circle, color = navItemColor,
                    onClick = { vm.onTogglePathClosed() },
                )
                uiState.selectedNodeIndex?.let { index ->
                    azRailSubItem(
                        id = "tool.nodeDelete", hostId = "grp.designTools", text = "Delete Node",
                        shape = AzButtonShape.NONE,
                        content = TablerIcons.X, color = navItemColor,
                        onClick = { vm.onDeletePathNode(index) },
                    )
                }
            }
        }
    }

"""
new_content = content[:target_index] + code_to_insert + content[target_index:]
with open(filepath, 'w') as f:
    f.write(new_content)
print('Success')
