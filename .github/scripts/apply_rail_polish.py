from pathlib import Path

MAIN = Path("app/src/main/java/com/hereliesaz/graffux/MainActivity.kt")
CATALOG = Path("app/src/main/java/com/hereliesaz/graffux/ToolCatalog.kt")
VERSION = Path("version.properties")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


s = MAIN.read_text()

# Area rails are opt-in. They are deliberately composable state rather than editor/document state:
# showing a toolbox does not mutate the artwork and should not enter undo history.
s = replace_once(
    s,
    '''    var showFigmaWindow by remember { mutableStateOf(false) }\n''',
    '''    var showFigmaWindow by remember { mutableStateOf(false) }\n    // Non-base workspaces get their own independent FLOATING AzNavRail hosts. The second title\n    // dropdown below merely decides which of these toolboxes are present; AzNavRail 11.26 owns\n    // their drag, screen-edge snap, rail-to-rail docking and group-drag behaviour.\n    var showAnimationRail by remember { mutableStateOf(false) }\n    var showModelRail by remember { mutableStateOf(false) }\n    var showReferenceRail by remember { mutableStateOf(false) }\n    var showFigmaRail by remember { mutableStateOf(false) }\n    var showExtensionsRail by remember { mutableStateOf(false) }\n''',
    "area rail state",
)

# Let the floating host itself light while the workspace/window it represents is live.
s = replace_once(
    s,
    '''    val activeClassifiers = activeRailClassifiers(\n        uiState, brushes, customBrushes,\n        modelWindowOpen = showModelDialog, toolOptionsOpen = showToolOptions,\n    )\n''',
    '''    val activeClassifiers = activeRailClassifiers(\n        uiState, brushes, customBrushes,\n        modelWindowOpen = showModelDialog, toolOptionsOpen = showToolOptions,\n    ).toMutableSet().apply {\n        if (uiState.isAnimationMode || uiState.isTimeLapseRecording) add("area.animation")\n        if (showModelDialog) add("area.model")\n        if (showReferenceWindow) add("area.reference")\n        if (showFigmaWindow) add("area.figma")\n        if (uiState.activePanel == EditorPanel.EXTENSIONS || showStoreDialog) add("area.extensions")\n    }.toSet()\n''',
    "floating host active classifiers",
)

# Add the new FLOATING workspace rails immediately after the base rail is declared. Multiple enabled
# hosts therefore participate in AzNavRail's new docking graph automatically instead of each app
# inventing another draggable-window system.
s = replace_once(
    s,
    '''                onForgetSelectionRequested = { pendingForgetSelectionName = it },\n            )\n\n            // The tools you actually reach for, one swipe from the bottom edge. The rail holds every\n''',
    '''                onForgetSelectionRequested = { pendingForgetSelectionName = it },\n            )\n\n            if (showAnimationRail) {\n                azUnattachedHostItem(\n                    id = "area.animation", text = "Animation",\n                    anchor = AzUnattachedAnchor.FLOATING,\n                    content = GraffuxIcons.MotionTween, color = navItemColor,\n                    shape = AzButtonShape.NONE_SQUARE, classifiers = setOf("area.animation"),\n                )\n                azRailSubItem(\n                    id = "tool.animation", hostId = "area.animation",\n                    text = if (uiState.isAnimationMode) "Close Animation Assist" else "Animation Assist",\n                    content = GraffuxIcons.MotionTween, color = navItemColor,\n                    classifiers = setOf("tool.animation"),\n                    onClick = { vm.onToggleAnimationMode() },\n                )\n                if (uiState.isAnimationMode) {\n                    azRailSubItem(\n                        id = "animation.play", hostId = "area.animation",\n                        text = if (uiState.isAnimationPlaying) "Pause" else "Play",\n                        content = GraffuxIcons.MotionTween, color = navItemColor,\n                        onClick = { vm.onToggleAnimationPlayback() },\n                    )\n                    azRailSubItem(\n                        id = "animation.previous", hostId = "area.animation", text = "Previous Frame",\n                        content = GraffuxIcons.Undo, color = navItemColor,\n                        onClick = { vm.onPreviousFrame() },\n                    )\n                    azRailSubItem(\n                        id = "animation.next", hostId = "area.animation", text = "Next Frame",\n                        content = GraffuxIcons.Redo, color = navItemColor,\n                        onClick = { vm.onNextFrame() },\n                    )\n                    azRailSubItem(\n                        id = "animation.add", hostId = "area.animation", text = "Add Frame",\n                        content = GraffuxIcons.LayerAdd, color = navItemColor,\n                        onClick = { vm.onAddFrame() },\n                    )\n                    azRailSubItem(\n                        id = "animation.onion", hostId = "area.animation",\n                        text = if (uiState.onionSkinEnabled) "Onion Skin Off" else "Onion Skin On",\n                        content = GraffuxIcons.LayerOpacity, color = navItemColor,\n                        onClick = { vm.onToggleOnionSkin() },\n                    )\n                    azRailSubItem(\n                        id = "animation.export", hostId = "area.animation", text = "Export Animation",\n                        content = GraffuxIcons.MotionTween, color = navItemColor,\n                        onClick = { vm.exportAnimation() },\n                    )\n                    azRailSubItem(\n                        id = "animation.timelapse", hostId = "area.animation",\n                        text = if (uiState.isTimeLapseRecording) "Stop Time-lapse" else "Start Time-lapse",\n                        content = GraffuxIcons.MotionTween, color = navItemColor,\n                        onClick = { vm.onToggleTimeLapseRecording() },\n                    )\n                }\n            }\n\n            if (showModelRail) {\n                azUnattachedHostItem(\n                    id = "area.model", text = "3D", anchor = AzUnattachedAnchor.FLOATING,\n                    content = GraffuxIcons.GuideIsometric, color = navItemColor,\n                    shape = AzButtonShape.NONE_SQUARE, classifiers = setOf("area.model"),\n                )\n                azRailSubItem(\n                    id = "tool.model", hostId = "area.model", text = "3D Model",\n                    content = GraffuxIcons.GuideIsometric, color = navItemColor,\n                    classifiers = setOf("tool.model"), onClick = { showModelDialog = true },\n                )\n                azRailSubItem(\n                    id = "model.choose", hostId = "area.model", text = "Choose Model",\n                    content = GraffuxIcons.GuideIsometric, color = navItemColor,\n                    onClick = { showModelDialog = true; modelPicker.launch(arrayOf("*/*")) },\n                )\n            }\n\n            if (showReferenceRail) {\n                azUnattachedHostItem(\n                    id = "area.reference", text = "Reference", anchor = AzUnattachedAnchor.FLOATING,\n                    content = GraffuxIcons.LayerReference, color = navItemColor,\n                    shape = AzButtonShape.NONE_SQUARE, classifiers = setOf("area.reference"),\n                )\n                azRailSubItem(\n                    id = "reference.open", hostId = "area.reference", text = "Reference Image",\n                    content = GraffuxIcons.LayerReference, color = navItemColor,\n                    onClick = { showReferenceWindow = true },\n                )\n                azRailSubItem(\n                    id = "reference.choose", hostId = "area.reference", text = "Choose Reference",\n                    content = GraffuxIcons.LayerReference, color = navItemColor,\n                    onClick = {\n                        showReferenceWindow = true\n                        referencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))\n                    },\n                )\n            }\n\n            if (showFigmaRail) {\n                azUnattachedHostItem(\n                    id = "area.figma", text = "Figma", anchor = AzUnattachedAnchor.FLOATING,\n                    content = GraffuxIcons.Artboard, color = navItemColor,\n                    shape = AzButtonShape.NONE_SQUARE, classifiers = setOf("area.figma"),\n                )\n                azRailSubItem(\n                    id = "figma.open", hostId = "area.figma", text = "Import from Figma",\n                    content = GraffuxIcons.Artboard, color = navItemColor,\n                    onClick = { showFigmaWindow = true },\n                )\n                azRailSubItem(\n                    id = "figma.export", hostId = "area.figma", text = "Export for Figma",\n                    content = GraffuxIcons.Artboard, color = navItemColor,\n                    onClick = { vm.exportForFigma() },\n                )\n            }\n\n            if (showExtensionsRail) {\n                azUnattachedHostItem(\n                    id = "area.extensions", text = "Extensions", anchor = AzUnattachedAnchor.FLOATING,\n                    content = GraffuxIcons.FilterGallery, color = navItemColor,\n                    shape = AzButtonShape.NONE_SQUARE, classifiers = setOf("area.extensions"),\n                )\n                azRailSubItem(\n                    id = "adj.extensions", hostId = "area.extensions", text = "Run Extension",\n                    content = GraffuxIcons.FilterGallery, color = navItemColor,\n                    classifiers = setOf("adj.extensions"), onClick = { vm.onExtensionsClicked() },\n                )\n                azRailSubItem(\n                    id = "extensions.manage", hostId = "area.extensions", text = "Manage Extensions",\n                    content = GraffuxIcons.FilterGallery, color = navItemColor,\n                    onClick = { showStoreDialog = true },\n                )\n                azRailSubItem(\n                    id = "extensions.get", hostId = "area.extensions", text = "Get Extensions",\n                    content = GraffuxIcons.FilterGallery, color = navItemColor,\n                    onClick = { openAzphaltStore() },\n                )\n            }\n\n            // The tools you actually reach for, one swipe from the bottom edge. The rail holds every\n''',
    "floating workspace rails",
)

# Turn the one top-right menu into two title-hosted menus. The original remains file/document actions;
# the hamburger controls the opt-in workspace rails. Reference/Figma/Extensions therefore stop being
# mixed in with Save/Share just because both happened to need somewhere to live.
s = replace_once(
    s,
    '''                if (!uiState.hideUiForCapture) AzDropdownMenu(navController = navController) {\n                    azConfig(design = AzDropdownDesign.MENU, dockingSide = if (uiState.isRightHanded) AzDockingSide.RIGHT else AzDockingSide.LEFT)\n''',
    '''                if (!uiState.hideUiForCapture) {\n                    AzDropdownMenu(navController = navController) {\n                        azConfig(\n                            design = AzDropdownDesign.MENU,\n                            dockingSide = if (uiState.isRightHanded) AzDockingSide.RIGHT else AzDockingSide.LEFT,\n                            trigger = AzDropdownTrigger.MoreVert,\n                            triggerPlacement = AzDropdownTriggerPlacement.TITLE,\n                        )\n''',
    "first dropdown title trigger",
)

for old, label in [
    ('                    menuItem(text = "Reference Image…", onClick = { showReferenceWindow = true })\n', "remove reference from file menu"),
    ('                    menuItem(text = "Import from Figma…", onClick = { showFigmaWindow = true })\n', "remove figma import from file menu"),
    ('                    menuItem(text = "Export for Figma", onClick = { vm.exportForFigma() })\n', "remove figma export from file menu"),
    ('                    menuItem(text = "Extensions", onClick = { showStoreDialog = true })\n', "remove extensions from file menu"),
]:
    s = replace_once(s, old, '', label)

s = replace_once(
    s,
    '''                    menuItem(text = "Settings", onClick = { showSettings = true })\n                }\n            }\n\n            // Onscreen Foreground Elements explicitly pinned over the canvas. Hidden while a bottom panel\n''',
    '''                        menuItem(text = "Settings", onClick = { showSettings = true })\n                    }\n\n                    AzDropdownMenu(navController = navController) {\n                        azConfig(\n                            design = AzDropdownDesign.MENU,\n                            dockingSide = if (uiState.isRightHanded) AzDockingSide.RIGHT else AzDockingSide.LEFT,\n                            showFooter = false,\n                            trigger = AzDropdownTrigger.Hamburger,\n                            triggerPlacement = AzDropdownTriggerPlacement.TITLE,\n                        )\n                        fun areaToggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) =\n                            azToggle(\n                                isChecked = checked,\n                                toggleOnText = label,\n                                toggleOffText = label,\n                                color = menuItemColor,\n                                onToggle = onToggle,\n                            )\n                        areaToggle("Animation rail", showAnimationRail) { showAnimationRail = it }\n                        areaToggle("3D rail", showModelRail) { showModelRail = it }\n                        areaToggle("Reference rail", showReferenceRail) { showReferenceRail = it }\n                        areaToggle("Figma rail", showFigmaRail) { showFigmaRail = it }\n                        areaToggle("Extensions rail", showExtensionsRail) { showExtensionsRail = it }\n                    }\n                }\n            }\n\n            // Onscreen Foreground Elements explicitly pinned over the canvas. Hidden while a bottom panel\n''',
    "second dropdown",
)

# A bordered button at rest looks selected. AzNavRail 11.19+ now draws the active ring even on
# NONE_SQUARE, so hosts/openers can be borderless until they actually become active.
s = replace_once(
    s,
    '''            color = railColor(id), shape = AzButtonShape.SQUARE,\n''',
    '''            color = railColor(id), shape = AzButtonShape.NONE_SQUARE,\n''',
    "host item resting border",
)

s = replace_once(s, 'private const val VECTOR_ID = "grp.vector"\n', 'private const val VECTOR_ID = "grp.vector"\nprivate const val TRANSFORM_ID = "grp.transform"\n', "transform group id")
s = replace_once(
    s,
    '''        EditorPanel.TRANSFORM -> add("adj.transform")\n''',
    '''        EditorPanel.TRANSFORM -> {\n            add("adj.transform")\n            add(TRANSFORM_ID)\n        }\n''',
    "transform group classifier",
)

# Brush already is a real toggle in toolItem: tapping the armed brush puts Tool.NONE back in hand.
# Make that inactive state the move/default state by removing the separate top-level Move button and
# putting all explicit transform modes behind one Crop & Transform nested rail.
old_transform = '''    stateItem("adj.transform", "Transform", GraffuxIcons.Move) { vm.onTransformClicked() }\n    TransformMode.entries.forEach { mode ->\n        stateItem(\n            id = "transform.${mode.name}", text = mode.label,\n            content = when (mode) {\n                TransformMode.FREEFORM -> GraffuxIcons.SelectTransform\n                TransformMode.DISTORT -> GraffuxIcons.PerspectiveTransform\n                TransformMode.WARP -> GraffuxIcons.PuppetWarp\n            },\n        ) { vm.onSetTransformMode(mode) }\n    }\n    if (uiState.transformMode != TransformMode.FREEFORM) {\n        azRailItem(\n            id = "transform.apply", text = "Apply",\n            content = GraffuxIcons.Success, color = activeColor,\n            onClick = { vm.onApplyWarp() },\n        )\n        azRailItem(\n            id = "transform.cancel", text = "Cancel",\n            content = GraffuxIcons.Close, color = navItemColor,\n            onClick = { vm.onCancelWarp() },\n        )\n    }\n\n'''
new_transform = '''    azNestedRail(\n        id = TRANSFORM_ID, classifiers = setOf(TRANSFORM_ID),\n        text = "Crop & Transform", content = GraffuxIcons.Crop,\n        color = railColor(TRANSFORM_ID), shape = AzButtonShape.NONE_SQUARE,\n        keepNestedRailOpen = true,\n    ) {\n        azRailItem(\n            id = "adj.transform", classifiers = setOf("adj.transform"),\n            text = "Move / Transform", content = GraffuxIcons.Move,\n            color = railColor("adj.transform"), shape = AzButtonShape.NONE_SQUARE,\n            onClick = { vm.onTransformClicked() },\n        )\n        TransformMode.entries.forEach { mode ->\n            val id = "transform.${mode.name}"\n            azRailItem(\n                id = id, classifiers = setOf(id), text = mode.label,\n                content = when (mode) {\n                    TransformMode.FREEFORM -> GraffuxIcons.SelectTransform\n                    TransformMode.DISTORT -> GraffuxIcons.PerspectiveTransform\n                    TransformMode.WARP -> GraffuxIcons.PuppetWarp\n                },\n                color = railColor(id), shape = AzButtonShape.NONE_SQUARE,\n                onClick = { vm.onSetTransformMode(mode) },\n            )\n        }\n        if (uiState.transformMode != TransformMode.FREEFORM) {\n            azRailItem(\n                id = "transform.apply", text = "Apply",\n                content = GraffuxIcons.Success, color = activeColor, shape = AzButtonShape.NONE_SQUARE,\n                onClick = { vm.onApplyWarp() },\n            )\n            azRailItem(\n                id = "transform.cancel", text = "Cancel",\n                content = GraffuxIcons.Close, color = navItemColor, shape = AzButtonShape.NONE_SQUARE,\n                onClick = { vm.onCancelWarp() },\n            )\n        }\n    }\n\n'''
s = replace_once(s, old_transform, new_transform, "crop and transform nested rail")

s = replace_once(
    s,
    '''        color = railColor(SELECT_ID), shape = AzButtonShape.SQUARE,\n''',
    '''        color = railColor(SELECT_ID), shape = AzButtonShape.NONE_SQUARE,\n''',
    "selection resting border",
)
s = replace_once(
    s,
    '''        color = railColor(VECTOR_ID), shape = AzButtonShape.SQUARE,\n''',
    '''        color = railColor(VECTOR_ID), shape = AzButtonShape.NONE_SQUARE,\n''',
    "vector resting border",
)
s = replace_once(
    s,
    '''        // Bordered: it opens the colour picker rather than selecting a tool.\n        shape = AzButtonShape.SQUARE,\n''',
    '''        // The active ring now supplies the state; no permanent border pretending to be selection.\n        shape = AzButtonShape.NONE_SQUARE,\n''',
    "color opener resting border",
)

# These workspaces now live in their opt-in floating rails rather than permanently occupying the base
# painting rail.
for old, label in [
    ('    stateItem("tool.animation", "Animation", GraffuxIcons.MotionTween) { vm.onToggleAnimationMode() }\n', "remove animation base item"),
    ('    stateItem("tool.model", "3D Model", GraffuxIcons.GuideIsometric) { onModelClicked() }\n', "remove model base item"),
    ('    stateItem("adj.extensions", "Run Extension", GraffuxIcons.FilterGallery, AzButtonShape.SQUARE) {\n        vm.onExtensionsClicked()\n    }\n', "remove extensions base item"),
]:
    s = replace_once(s, old, '', label)

# The Layers panel is itself a FLOATING unattached rail now, so it participates in the new docking
# behaviour. Its children remain azRailRelocItem: that is also the type AzNavRail uses to wire the
# long-press hidden menu under an unattached host in 11.26.
s = replace_once(s, '        anchor = AzUnattachedAnchor.OPPOSITE,\n', '        anchor = AzUnattachedAnchor.FLOATING,\n', "layers floating anchor")
s = replace_once(
    s,
    '''        shape = AzButtonShape.SQUARE,\n    )\n    uiState.layers.filter { it.parentId == null }.reversed().forEach { layer ->\n''',
    '''        shape = AzButtonShape.NONE_SQUARE,\n    )\n    uiState.layers.filter { it.parentId == null }.reversed().forEach { layer ->\n''',
    "layers resting border",
)

# Deletion should not become undiscoverable merely because the long-press menu was broken in an old
# library build. Keep the hidden-menu Delete and add one visible action for the active layer.
s = replace_once(
    s,
    '''    azRailSubItem(\n        id = "layer.add", hostId = "grp.layers", text = "Add Layer",\n        content = GraffuxIcons.LayerAdd,\n        color = navItemColor,\n        onClick = { vm.onAddBlankLayer() },\n    )\n\n''',
    '''    azRailSubItem(\n        id = "layer.add", hostId = "grp.layers", text = "Add Layer",\n        content = GraffuxIcons.LayerAdd,\n        color = navItemColor,\n        onClick = { vm.onAddBlankLayer() },\n    )\n    uiState.activeLayerId?.let { activeId ->\n        azRailSubItem(\n            id = "layer.deleteActive", hostId = "grp.layers", text = "Delete Active Layer",\n            content = GraffuxIcons.LayerDelete, color = navItemColor,\n            onClick = {\n                val active = uiState.layers.firstOrNull { it.id == activeId }\n                if (active?.type == LayerType.GROUP) vm.onDeleteGroup(activeId)\n                else vm.onLayerRemoved(activeId)\n            },\n        )\n    }\n\n''',
    "visible layer delete",
)

# Remove the obsolete warning that nested reloc hidden menus are impossible. AzNavRail 11.26 now
# threads hidden-menu state/callbacks through NestedRail for unattached hosts.
obsolete = '''        // KNOWN LIBRARY LIMITATION (AzNavRail, not fixable from this repo): a child rendered here\n        // ends up in the parent's `nestedRailItems`, drawn by NestedRail.kt's `NestedItemWrapper`\n        // rather than the top-level `RailContent`. Only `RailContent` wires the long-press gesture\n        // that opens a hidden menu (see RailItems.kt's `dragModifier`, gated on `item.isRelocItem`);\n        // `NestedItemWrapper` wires nothing but a plain `onClick`. So every listItem/inputItem below\n        // — Adjust, Rename, Merge Down, Delete, and the rest — is unreachable on a layer that is\n        // currently inside a group; tapping it only activates it. The one way back to those actions\n        // today is "Ungroup" on the group's own menu (reachable — the group container is a top-level\n        // item, not a nested one). Filed upstream against HereLiesAz/aznavrail; fixing it here would\n        // mean bypassing the rail DSL this whole screen is built on, which is a bigger change than\n        // this limitation warrants.\n'''
s = replace_once(s, obsolete, '', "remove obsolete hidden-menu limitation")

# The current palette is a giant solid oval, so a white active colour turns it into a white blob.
# Draw the familiar kidney-shaped painter's palette as an outlined object with separate paint wells;
# the active colour gets the large well, while the small wells keep the silhouette readable even when
# the active colour is white or black.
old_palette = '''/**\n * The colour item's content: [color] shown inside a painter's-palette silhouette rather than a\n * plain square chip — a shape that reads as "this is the paint you're about to use" the way a flat\n * colour-filled square doesn't, and one Procreate's own colour puck deliberately isn't a square\n * either. Solid, not an outline: the whole body is filled with [color], the way a loaded palette\n * actually looks, with a thin pale stroke so the silhouette stays legible when [color] is close to\n * the rail's own near-black background.\n */\n@Composable\nprivate fun PaletteSwatch(color: Color) {\n    Canvas(Modifier.fillMaxSize()) {\n        val w = size.width\n        val h = size.height\n        val body = Path().apply { addOval(Rect(0f, 0f, w, h * 0.86f)) }\n        // The thumb notch: a smaller circle overlapping the body's lower edge. Unioned in first, it\n        // bulges the silhouette outward there before the hole below is cut from that same bulge —\n        // exactly where a real palette's thumb rests.\n        val notch = Path().apply { addOval(Rect(w * 0.30f, h * 0.58f, w * 0.70f, h * 0.98f)) }\n        val bodyWithNotch = Path().apply { op(body, notch, PathOperation.Union) }\n        val hole = Path().apply { addOval(Rect(w * 0.41f, h * 0.70f, w * 0.59f, h * 0.90f)) }\n        val silhouette = Path().apply { op(bodyWithNotch, hole, PathOperation.Difference) }\n        drawPath(silhouette, color = color)\n        drawPath(silhouette, color = Color.White.copy(alpha = 0.25f), style = Stroke(width = 1.dp.toPx()))\n    }\n}\n'''
new_palette = '''/** A real painter's-palette glyph: silhouette + thumb hole + separate paint wells. */\n@Composable\nprivate fun PaletteSwatch(color: Color) {\n    Canvas(Modifier.fillMaxSize()) {\n        val w = size.width\n        val h = size.height\n        val body = Path().apply {\n            moveTo(w * 0.88f, h * 0.42f)\n            cubicTo(w * 0.88f, h * 0.16f, w * 0.67f, h * 0.05f, w * 0.43f, h * 0.07f)\n            cubicTo(w * 0.18f, h * 0.09f, w * 0.06f, h * 0.28f, w * 0.08f, h * 0.52f)\n            cubicTo(w * 0.10f, h * 0.78f, w * 0.30f, h * 0.94f, w * 0.52f, h * 0.84f)\n            cubicTo(w * 0.64f, h * 0.78f, w * 0.59f, h * 0.66f, w * 0.72f, h * 0.63f)\n            cubicTo(w * 0.82f, h * 0.61f, w * 0.88f, h * 0.54f, w * 0.88f, h * 0.42f)\n            close()\n        }\n        val thumb = Path().apply { addOval(Rect(w * 0.55f, h * 0.62f, w * 0.72f, h * 0.80f)) }\n        val silhouette = Path().apply { op(body, thumb, PathOperation.Difference) }\n        drawPath(silhouette, color = Color.White.copy(alpha = 0.10f))\n        drawPath(silhouette, color = Color.White.copy(alpha = 0.88f), style = Stroke(width = 1.5.dp.toPx()))\n\n        fun paintWell(center: Offset, radius: Float, paint: Color) {\n            drawCircle(Color.Black.copy(alpha = 0.72f), radius = radius + 1.dp.toPx(), center = center)\n            drawCircle(paint, radius = radius, center = center)\n            drawCircle(\n                Color.White.copy(alpha = 0.55f), radius = radius, center = center,\n                style = Stroke(width = 0.7.dp.toPx()),\n            )\n        }\n\n        val r = minOf(w, h) * 0.085f\n        paintWell(Offset(w * 0.31f, h * 0.29f), r, Cyan)\n        paintWell(Offset(w * 0.51f, h * 0.23f), r, Color(0xFFFF2DAA))\n        paintWell(Offset(w * 0.68f, h * 0.34f), r, Color(0xFFFFC107))\n        paintWell(Offset(w * 0.34f, h * 0.56f), r * 1.35f, color)\n    }\n}\n'''
s = replace_once(s, old_palette, new_palette, "painter palette")

MAIN.write_text(s)

catalog = CATALOG.read_text()
catalog = replace_once(
    catalog,
    '    Tool.FILL to ToolEntry("tool.fill", "Fill", GraffuxIcons.Colordrop),\n',
    '    Tool.FILL to ToolEntry("tool.fill", "Fill", GraffuxIcons.Fill),\n',
    "paint bucket icon",
)
CATALOG.write_text(catalog)

version = VERSION.read_text()
version = replace_once(version, "versionBuild=495", "versionBuild=496", "version build")
version = replace_once(version, "versionPatch=11", "versionPatch=12", "version patch")
VERSION.write_text(version)

print("Applied rail polish patch successfully")
