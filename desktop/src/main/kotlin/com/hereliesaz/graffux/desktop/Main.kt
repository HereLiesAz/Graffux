@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.MenuBar
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hereliesaz.aznavrail.AzAppMeta
import com.hereliesaz.aznavrail.AzAboutPage
import com.hereliesaz.aznavrail.LocalAzAppMeta
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.azphalt.wgpu.MultipassSettings
import kotlin.math.roundToInt

/** Longest drying transition the Tool Options slider offers, ms. */
private const val MAX_TRANSITION_MS = 400f

/** A small, real palette for quick picks -- the last swatch in the row opens [ColorWheel], a real
 *  HSV disc picker, for anything not in this fixed set. */
private val PALETTE = listOf(
    Color(0xFF141414), Color(0xFFFFFFFF), Color(0xFFE53935), Color(0xFF1E88E5),
    Color(0xFF43A047), Color(0xFFFDD835), Color(0xFF8E24AA), Color(0xFFFF6F00),
)

/**
 * Entry point for the real Graffux desktop app (Linux, Windows, macOS) -- see [DesktopStampCanvas]
 * for the shared-engine drawing surface and DESKTOP.md at the repo root for what's verified vs.
 * deferred. The chrome follows the approved Desktop / DesignDesktop boards: the tool rail at the
 * top-left, the layers rail under the logo menu at the top-right (beside the tool rail while the
 * properties panel is docked), the properties side panel, and a menu bar. The menu bar and the logo
 * menu list the same [CommandMenu]s, each with its key; Cmd on macOS, Ctrl elsewhere ([Shortcut]).
 */
fun main() = application {
    // Tall enough for the tool rail's every item without scrolling (see DESKTOP.md).
    val windowState = rememberWindowState(position = WindowPosition(Alignment.Center), size = DpSize(1280.dp, 1000.dp))
    val canvasState = remember { CanvasState() }
    val recent = remember { RecentProjects() }
    var recentFiles by remember { mutableStateOf(recent.list()) }
    var documentFile by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf<Status?>(null) }
    var showProperties by remember { mutableStateOf(true) }
    var showToolOptions by remember { mutableStateOf(true) }
    var showAbout by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<java.awt.Frame?>(null) }
    val documentName = documentFile?.nameWithoutExtension ?: "Untitled"

    // Every file action reports here instead of throwing out of a key or menu handler.
    fun saveTo(file: File): Boolean = try {
        val snapshot = canvasState.currentSnapshot()
        ProjectFile.save(snapshot, file)
        canvasState.markSaved(snapshot)
        documentFile = file
        recentFiles = recent.add(file)
        status = Status("Saved ${file.absolutePath}")
        true
    } catch (e: Exception) {
        status = Status("Save failed: ${e.message ?: e::class.simpleName}", error = true)
        false
    }

    fun saveAs(): Boolean = FileDialogs.saveProject(frame, "$documentName.${ProjectFile.EXTENSION}")?.let(::saveTo) ?: false
    fun save(): Boolean = documentFile?.let(::saveTo) ?: saveAs()

    /** True when the current document may be replaced: it is saved, or the user said discard. */
    fun mayDiscard(): Boolean = !canvasState.isDirty || when (FileDialogs.confirmUnsaved(frame, documentName)) {
        FileDialogs.Unsaved.SAVE -> save()
        FileDialogs.Unsaved.DISCARD -> true
        FileDialogs.Unsaved.CANCEL -> false
    }

    fun newDocument() {
        if (!mayDiscard()) return
        canvasState.newDocument()
        documentFile = null
        status = null
    }

    fun open(chosen: File? = null) {
        if (!mayDiscard()) return
        val file = chosen ?: FileDialogs.openProject(frame) ?: return
        try {
            canvasState.load(ProjectFile.load(file))
            documentFile = file
            recentFiles = recent.add(file)
            status = Status("Opened ${file.absolutePath}")
        } catch (e: Exception) {
            if (!file.isFile) recentFiles = recent.remove(file)
            status = Status("Open failed: ${e.message ?: e::class.simpleName}", error = true)
        }
    }

    fun exportPng() {
        val file = FileDialogs.exportPng(frame, "$documentName.png") ?: return
        status = try {
            if (canvasState.exportPngTo(file)) Status("Exported ${file.absolutePath}") else Status("Nothing to export", error = true)
        } catch (e: Exception) {
            Status("Export failed: ${e.message ?: e::class.simpleName}", error = true)
        }
    }

    fun quit() {
        if (mayDiscard()) exitApplication()
    }

    val active = canvasState.activeLayer
    val activeIndex = canvasState.layers.indexOf(active)
    val menus = listOf(
        CommandMenu(
            "File",
            listOf(
                Command("New", Shortcuts.New) { newDocument() },
                Command("Open…", Shortcuts.Open) { open() },
                null,
                Command("Save", Shortcuts.Save) { save() },
                Command("Save As…", Shortcuts.SaveAs) { saveAs() },
                Command("Export PNG…", Shortcuts.ExportPng, enabled = active != null) { exportPng() },
                null,
                Command("Quit", Shortcuts.Quit) { quit() },
            ),
        ),
        CommandMenu(
            "Edit",
            listOf(
                Command("Undo", Shortcuts.Undo, enabled = canvasState.canUndo) { canvasState.undo() },
                Command("Redo", Shortcuts.Redo, enabled = canvasState.canRedo) { canvasState.redo() },
                null,
                Command("Clear Layer", enabled = active != null) { canvasState.clear() },
            ),
        ),
        CommandMenu(
            "Layer",
            listOf(
                Command("New Layer", Shortcuts.NewLayer, enabled = active != null) { canvasState.addLayer() },
                Command("Duplicate Layer", Shortcuts.DuplicateLayer, enabled = active != null) { active?.let { canvasState.duplicateLayer(it.id) } },
                Command("Merge Down", Shortcuts.MergeDown, enabled = activeIndex > 0) { active?.let { canvasState.mergeDown(it.id) } },
                null,
                Command("Move Up", Shortcuts.LayerUp, enabled = active != null && activeIndex < canvasState.layers.lastIndex) { active?.let { canvasState.moveLayer(it.id, 1) } },
                Command("Move Down", Shortcuts.LayerDown, enabled = activeIndex > 0) { active?.let { canvasState.moveLayer(it.id, -1) } },
                null,
                Command("Hide Layer", enabled = active != null, checked = active?.visible == false) { active?.let { canvasState.setVisible(it.id, !it.visible) } },
                Command("Rename…", enabled = active != null) { active?.let { l -> FileDialogs.askName(frame, l.name)?.let { canvasState.rename(l.id, it) } } },
                Command("Delete Layer", enabled = active != null) { active?.let { canvasState.deleteLayer(it.id) } },
            ),
        ),
        CommandMenu(
            "View",
            listOf(
                Command("Properties Panel", Shortcuts.Properties, checked = showProperties) { showProperties = !showProperties },
                Command("Tool Options", checked = showToolOptions) { showToolOptions = !showToolOptions },
            ),
        ),
        CommandMenu("Help", listOf(Command("About Graffux") { showAbout = true })),
    )

    // The same app icon Android's launcher uses (`branding/icon-512.png`).
    val windowIcon = remember {
        object {}.javaClass.classLoader.getResourceAsStream("icon-512.png")?.use { loadImageBitmap(it) }
    }
    Window(
        onCloseRequest = ::quit,
        title = "$documentName${if (canvasState.isDirty) " •" else ""} — Graffux",
        state = windowState,
        icon = windowIcon?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it) },
        onKeyEvent = { event ->
            // The menu bar binds every listed key itself; this adds the common alternates only.
            val primary = if (IsMac) event.isMetaPressed else event.isCtrlPressed
            if (event.type == KeyEventType.KeyDown && primary && !IsMac && event.key == Key.Y && canvasState.canRedo) {
                canvasState.redo()
                true
            } else {
                false
            }
        },
    ) {
        SideEffect { frame = window }
        MenuBar {
            menus.forEach { menu ->
                Menu(menu.title) {
                    menu.items.forEach { command ->
                        when {
                            command == null -> Separator()
                            command.checked != null -> CheckboxItem(command.label, command.checked, enabled = command.enabled, shortcut = command.shortcut?.keyShortcut(), onCheckedChange = { command.run() })
                            else -> Item(command.label, enabled = command.enabled, shortcut = command.shortcut?.keyShortcut(), onClick = command.run)
                        }
                        if (command?.label == "Open…") {
                            Menu("Open Recent", enabled = recentFiles.isNotEmpty()) {
                                recentFiles.forEach { file -> Item(file.name, onClick = { open(file) }) }
                                Separator()
                                Item("Clear Recent", onClick = { recentFiles = recent.clear() })
                            }
                        }
                    }
                }
            }
        }
        GraffuxDesktopTheme {
            CompositionLocalProvider(
                // Desktop has no OS-level app name/icon the way an Android manifest does, so the
                // About reader is given them by hand.
                LocalAzAppMeta provides AzAppMeta(name = "Graffux", packageId = "com.hereliesaz.graffux"),
            ) {
                GraffuxDesktopApp(
                    canvasState = canvasState,
                    menus = menus,
                    recentFiles = recentFiles,
                    onOpenRecent = { open(it) },
                    documentName = documentName,
                    status = status,
                    onSave = { save() },
                    showProperties = showProperties,
                    showToolOptions = showToolOptions,
                    onShowToolOptions = { showToolOptions = it },
                    onRename = { l -> FileDialogs.askName(frame, l.name)?.let { canvasState.rename(l.id, it) } },
                )
                if (showAbout) AboutReader(onDismiss = { showAbout = false })
            }
        }
    }
}

/** A line at the window's foot: the last save, open or export, or why it failed. */
internal data class Status(val text: String, val error: Boolean = false)

@Composable
private fun GraffuxDesktopApp(
    canvasState: CanvasState,
    menus: List<CommandMenu>,
    recentFiles: List<File>,
    onOpenRecent: (File) -> Unit,
    documentName: String,
    status: Status?,
    onSave: () -> Unit,
    showProperties: Boolean,
    showToolOptions: Boolean,
    onShowToolOptions: (Boolean) -> Unit,
    onRename: (DesktopLayer) -> Unit,
) {
    var brushRadius by remember { mutableFloatStateOf(24f) }
    var brushFlow by remember { mutableFloatStateOf(1f) }
    var selectedBrush by remember { mutableStateOf<AzphaltBrush>(BuiltInBrushes.presets.first()) }
    var selectedColor by remember { mutableStateOf(PALETTE.first()) }
    var showColorWheel by remember { mutableStateOf(false) }
    // Multipass drying (experimental, GPU canvas only): off by default.
    var multipass by remember { mutableStateOf(MultipassSettings()) }
    // Real icons (the same master SVGs Android's `GraffuxIcons` generates from) -- see Icons.kt.
    val brushIcon = GraffuxDesktopIcons.brush()
    val undoIcon = GraffuxDesktopIcons.undo()
    val redoIcon = GraffuxDesktopIcons.redo()
    val clearIcon = GraffuxDesktopIcons.clear()
    val saveIcon = GraffuxDesktopIcons.save()

    // Which brush set's list is open beside the tool rail; which layer's menu beside the layers
    // rail; and the logo menu's page (null: shut; "" its top level; else a menu's title).
    var openSet by remember { mutableStateOf<String?>(null) }
    var layerMenuFor by remember { mutableStateOf<String?>(null) }
    var logoPage by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        // Android's own screens sit on `MaterialTheme.colorScheme.background` (black); without an
        // explicit Surface the window's content pane defaults to plain white.
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    CompositionLocalProvider(LocalMultipassSettings provides multipass) {
                        DesktopStampCanvas(
                            state = canvasState,
                            brush = selectedBrush,
                            brushRadiusPx = brushRadius,
                            colorArgb = selectedColor.toArgb(),
                            flow = brushFlow,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Procreate-style floating, draggable panels (FloatingWindow.kt).
                    if (showToolOptions) {
                        FloatingWindow(
                            title = "Tool Options",
                            onDismiss = { onShowToolOptions(false) },
                            initialOffset = Offset(120f, 80f),
                        ) {
                    Text("Brush: ${selectedBrush.name} — ${brushRadius.toInt()}px")
                    Slider(
                        value = brushRadius,
                        onValueChange = { brushRadius = it },
                        valueRange = 4f..96f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Android's brushFlow (feature:editor's EditorViewModel) -- per-dab colour
                    // build-up along a stroke, same shared engine param compositeTileParallel
                    // already accepted here but had hardcoded to 1f until now.
                    Text("Flow: ${(brushFlow * 100).toInt()}%")
                    Slider(
                        value = brushFlow,
                        onValueChange = { brushFlow = it },
                        valueRange = 0.05f..1f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Color")
                    Row {
                        PALETTE.forEach { color ->
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (color == selectedColor) 3.dp else 1.dp,
                                        color = if (color == selectedColor) GraffuxColors.HotPink else GraffuxColors.Gray,
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        selectedColor = color
                                        showColorWheel = false
                                    },
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        // The swatch row above is a fixed 8-colour palette, not the full HSV
                        // wheel Android's ColorPickerDialog offers -- this toggle opens a real
                        // one (see ColorWheel.kt), in its own floating panel, rather than
                        // leaving custom colour choice unreachable.
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(selectedColor)
                                .border(
                                    width = if (showColorWheel) 3.dp else 1.dp,
                                    color = if (showColorWheel) GraffuxColors.HotPink else GraffuxColors.Gray,
                                    shape = CircleShape,
                                )
                                .clickable { showColorWheel = !showColorWheel },
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Experimental: each dab shows at once as a draft of the same brush and
                    // settles to full quality as the GPU catches up. The committed result is
                    // identical either way. Takes effect on the next stroke.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = multipass.enabled,
                            onCheckedChange = { multipass = multipass.copy(enabled = it) },
                        )
                        Text("Multipass drying (experimental)")
                    }
                    if (multipass.enabled) {
                        Text("Drying transition: ${multipass.transitionMs.toInt()} ms")
                        Slider(
                            value = multipass.transitionMs,
                            onValueChange = { multipass = multipass.copy(transitionMs = it) },
                            valueRange = 0f..MAX_TRANSITION_MS,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
                    if (showColorWheel) {
                        FloatingWindow(
                            title = "Color",
                            onDismiss = { showColorWheel = false },
                            initialOffset = Offset(120f, 340f),
                        ) {
                            ColorWheel(
                                currentColor = selectedColor,
                                onColorSelected = { selectedColor = it },
                            )
                        }
                    }
                    status?.let {
                        Text(
                            it.text,
                            color = if (it.error) Color(0xFFE53935) else ShortcutInk,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = RailSideInset + RailWidth + RailGap, vertical = 12.dp),
                        )
                    }
                }
                if (showProperties) PropertiesPanel(canvasState, documentName)
            }
        }
        // The tool rail: one button per bundled brush set (its list opens beside it), then the
        // canvas actions, each with the key that does the same thing in its corner.
        Row(
            Modifier.align(Alignment.TopStart).padding(start = RailSideInset, top = RailTopInset, bottom = RailGap),
            verticalAlignment = Alignment.Top,
        ) {
            RailPill {
                BuiltInBrushes.sets.forEach { set ->
                    RailButton(
                        label = set.name,
                        onClick = { openSet = if (openSet == set.name) null else set.name },
                        text = set.name.take(2),
                        active = set.brushes.any { it.name == selectedBrush.name },
                    )
                }
                RailDivider()
                RailButton("Undo", { canvasState.undo() }, icon = undoIcon, enabled = canvasState.canUndo, shortcut = Shortcuts.Undo.label)
                RailButton("Redo", { canvasState.redo() }, icon = redoIcon, enabled = canvasState.canRedo, shortcut = Shortcuts.Redo.label)
                RailButton("Clear Layer", { canvasState.clear() }, icon = clearIcon, enabled = canvasState.activeLayer != null)
                RailButton("Save", onSave, icon = saveIcon, shortcut = Shortcuts.Save.label)
                // Reopens the tool-options panel once its own close button has been used.
                RailButton("Tool Options", { onShowToolOptions(!showToolOptions) }, icon = brushIcon, active = showToolOptions)
            }
            if (showProperties) {
                // Docked panel: the layers rail moves beside the tool rail (spec/design-tools.md).
                LayersColumn(canvasState, layerMenuFor, { layerMenuFor = it }, onRename, menuOnLeft = false, modifier = Modifier.padding(start = RailGap))
            }
            BuiltInBrushes.sets.firstOrNull { it.name == openSet }?.let { set ->
                RailMenu(
                    title = set.name,
                    rows = set.brushes.map { it.name },
                    lit = selectedBrush.name,
                    onPick = { index -> selectedBrush = set.brushes[index]; openSet = null },
                    modifier = Modifier.padding(start = RailGap),
                )
            }
        }
        if (!showProperties) {
            LayersColumn(
                canvasState, layerMenuFor, { layerMenuFor = it }, onRename, menuOnLeft = true,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = RailTopInset + RailButtonSize + RailSideInset, end = RailSideInset),
            )
        }
        LogoMenu(
            page = logoPage,
            onPage = { logoPage = it },
            menus = menus,
            recentFiles = recentFiles,
            onOpenRecent = onOpenRecent,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** The layers rail with its layer menu beside it, on the side away from the window's edge. */
@Composable
private fun LayersColumn(
    state: CanvasState,
    menuFor: String?,
    onMenuFor: (String?) -> Unit,
    onRename: (DesktopLayer) -> Unit,
    menuOnLeft: Boolean,
    modifier: Modifier = Modifier,
) {
    val rail = @Composable {
        LayersRail(
            layers = state.layers,
            activeLayerId = state.activeLayerId,
            callbacks = LayersRailCallbacks(
                onActivate = { state.setActive(it); onMenuFor(null) },
                onOpenMenu = { id -> state.setActive(id); onMenuFor(if (menuFor == id) null else id) },
                onMove = { id, by -> state.moveLayer(id, by) },
                onAdd = { state.addLayer(); onMenuFor(null) },
            ),
        )
    }
    val menu = @Composable {
        state.layers.firstOrNull { it.id == menuFor }?.let { layer ->
            val index = state.layers.indexOf(layer)
            val rows = listOf(
                "Rename…" to { onRename(layer) },
                (if (layer.visible) "Hide" else "Show") to { state.setVisible(layer.id, !layer.visible) },
                "Duplicate" to { state.duplicateLayer(layer.id) },
                "Merge Down" to { state.mergeDown(layer.id) },
                "Move Up" to { state.moveLayer(layer.id, 1) },
                "Move Down" to { state.moveLayer(layer.id, -1) },
                "Clear" to { state.clear() },
                "Delete" to { state.deleteLayer(layer.id) },
            )
            RailMenu(
                title = layer.name,
                rows = rows.map { it.first },
                lit = null,
                trailing = listOf(null, null, Shortcuts.DuplicateLayer.label, Shortcuts.MergeDown.label, Shortcuts.LayerUp.label, Shortcuts.LayerDown.label, null, null),
                enabled = listOf(true, true, true, index > 0, index < state.layers.lastIndex, index > 0, true, true),
                onPick = { i -> onMenuFor(null); rows[i].second() },
                modifier = Modifier.padding(horizontal = RailGap),
            )
        }
    }
    Row(modifier, verticalAlignment = Alignment.Top) {
        if (menuOnLeft) { menu(); rail() } else { rail(); menu() }
    }
}

/**
 * The logo menu (Android's LogoMenu.kt): the Graffux mark at the top-right corner and the app's
 * menu dropping from it -- the menu bar's menus as pages, each row with its key.
 */
@Composable
private fun LogoMenu(
    page: String?,
    onPage: (String?) -> Unit,
    menus: List<CommandMenu>,
    recentFiles: List<File>,
    onOpenRecent: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = page != null
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier.padding(top = RailTopInset, end = if (open) RailSideInset else 18.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(RailTargetSize)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = if (open) "Close menu" else "Open menu") { onPage(if (open) null else "") },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(RailButtonSize)
                    .clip(CircleShape)
                    .then(if (open) Modifier.background(Color(0xFF161618)).border(1.dp, RailInk, CircleShape) else Modifier),
            ) {
                Image(rememberVectorPainter(RailGlyphs.LogoMark), contentDescription = "Graffux menu", modifier = Modifier.size(26.dp))
            }
        }
        if (!open) return@Column
        val menu = menus.firstOrNull { it.title == page }
        val back = "‹ Back"
        when {
            page == "" -> {
                val titles = menus.map { it.title } + listOf(MENU_DIVIDER, "Open Recent")
                RailMenu(
                    title = null,
                    rows = titles,
                    lit = null,
                    trailing = titles.map { if (it == MENU_DIVIDER) null else "›" },
                    enabled = titles.map { it != "Open Recent" || recentFiles.isNotEmpty() },
                    onPick = { onPage(titles[it]) },
                    modifier = Modifier.padding(top = RailGap),
                )
            }
            page == "Open Recent" -> RailMenu(
                title = "Open Recent",
                rows = listOf(back) + recentFiles.map { it.name },
                lit = null,
                onPick = { i -> if (i == 0) onPage("") else { onPage(null); onOpenRecent(recentFiles[i - 1]) } },
                modifier = Modifier.padding(top = RailGap),
            )
            menu != null -> {
                val items = menu.items
                RailMenu(
                    title = menu.title,
                    rows = listOf(back) + items.map { c -> c?.let { if (it.checked == true) "${it.label} ●" else it.label } ?: MENU_DIVIDER },
                    lit = null,
                    trailing = listOf(null) + items.map { it?.shortcut?.label },
                    enabled = listOf(true) + items.map { it?.enabled ?: true },
                    onPick = { i -> if (i == 0) onPage("") else items[i - 1]?.let { onPage(null); it.run() } },
                    modifier = Modifier.padding(top = RailGap),
                )
            }
        }
    }
}

/** AzNavRail's own About reader for this repository. */
@Composable
private fun AboutReader(onDismiss: () -> Unit) {
    AzAboutPage(onDismiss = onDismiss, repoUrl = "https://github.com/HereLiesAz/Graffux")
}
