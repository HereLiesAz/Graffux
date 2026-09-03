package com.hereliesaz.graffux.desktop

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
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzAppMeta
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.LocalAzAppMeta
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import kotlin.math.roundToInt

/** A small, real palette for quick picks -- the last swatch in the row opens [ColorWheel], a real
 *  HSV disc picker, for anything not in this fixed set. */
private val PALETTE = listOf(
    Color(0xFF141414), Color(0xFFFFFFFF), Color(0xFFE53935), Color(0xFF1E88E5),
    Color(0xFF43A047), Color(0xFFFDD835), Color(0xFF8E24AA), Color(0xFFFF6F00),
)

/**
 * Entry point for the real Graffux desktop app (Linux + Windows) — see [DesktopStampCanvas] for
 * the shared-engine drawing surface and DESKTOP.md at the repo root for what's verified vs.
 * explicitly deferred. The tool navigation is the real AzNavRail (`aznavrail-cmp`, the Compose
 * Multiplatform port of the same DSL the Android app's `AzHostActivityLayout`/`azConfig`/
 * `azRailItem` use — this is not a substitute UI, it is the same library), not a placeholder. The
 * brush presets on the rail are [BuiltInBrushes.presets] — the same shared, pure-Kotlin brush
 * definitions the Android app ships with, not desktop-only stand-ins.
 */
fun main() = application {
    // The library's default 800x600 window ran out of vertical room for a 6th rail item (Undo +
    // Redo pushed the rail's total height past 600px, so Redo silently rendered off the bottom of
    // the window with no scroll/overflow indicator -- not a rail capacity bug, just too small a
    // window for how many tools this app now has). 1000x1100 leaves real headroom to keep adding
    // rail items without repeating this (bumped from an earlier 900 while testing a 9th item,
    // azAbout()'s auto "?" button -- see DESKTOP.md).
    val windowState = rememberWindowState(position = WindowPosition(Alignment.Center), size = DpSize(1000.dp, 1100.dp))
    // Hoisted here (rather than inside GraffuxDesktopApp, where every other piece of UI state
    // lives) purely so Window's own onKeyEvent below -- Ctrl+Z / Ctrl+Y, a desktop convention
    // Android's touch-only editor has no equivalent shortcut for -- can reach it directly. Same
    // reason `lastSavedPath` (Ctrl+S's own feedback label) lives here too.
    val canvasState = remember { CanvasState() }
    var lastSavedPath by remember { mutableStateOf<String?>(null) }
    // The same app icon Android's launcher uses (`branding/icon-512.png`) instead of the
    // unbranded default JVM coffee-cup icon — part of UI parity, not just the in-window theme.
    val windowIcon = remember {
        object {}.javaClass.classLoader.getResourceAsStream("icon-512.png")?.use { loadImageBitmap(it) }
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Graffux",
        state = windowState,
        icon = windowIcon?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it) },
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                false
            } else when {
                event.key == Key.Z && !event.isShiftPressed -> { canvasState.undo(); true }
                event.key == Key.Z && event.isShiftPressed -> { canvasState.redo(); true }
                event.key == Key.Y -> { canvasState.redo(); true }
                event.key == Key.S -> { lastSavedPath = canvasState.exportPng()?.absolutePath; true }
                else -> false
            }
        },
    ) {
        GraffuxDesktopTheme {
            CompositionLocalProvider(
                // Desktop has no OS-level app name/icon the way an Android manifest does, so this
                // is provided by hand (matches the pattern aznavrail's own CMP demo app uses).
                LocalAzAppMeta provides AzAppMeta(name = "Graffux", packageId = "com.hereliesaz.graffux"),
            ) {
                GraffuxDesktopApp(canvasState, lastSavedPath) { lastSavedPath = it }
            }
        }
    }
}

@Composable
private fun GraffuxDesktopApp(
    canvasState: CanvasState,
    lastSavedPath: String?,
    onSaved: (String?) -> Unit,
) {
    val navController = rememberNavController()
    var brushRadius by remember { mutableFloatStateOf(24f) }
    var brushFlow by remember { mutableFloatStateOf(1f) }
    var selectedBrush by remember { mutableStateOf<AzphaltBrush>(BuiltInBrushes.presets.first()) }
    var selectedColor by remember { mutableStateOf(PALETTE.first()) }
    var showColorWheel by remember { mutableStateOf(false) }
    var showToolOptions by remember { mutableStateOf(true) }
    // Real icons (the same master SVGs Android's `GraffuxIcons` generates from), not text-only
    // rail labels -- see Icons.kt. Resolved here, in this composable's own scope, since the
    // `AzHostActivityLayout` DSL block below is not itself `@Composable`.
    val brushIcon = GraffuxDesktopIcons.brush()
    val undoIcon = GraffuxDesktopIcons.undo()
    val redoIcon = GraffuxDesktopIcons.redo()
    val clearIcon = GraffuxDesktopIcons.clear()
    val saveIcon = GraffuxDesktopIcons.save()

    AzHostActivityLayout(navController = navController, initiallyExpanded = true) {
        // Android's real rail accent (`core:design`'s `DarkColorScheme`), not an invented purple.
        azTheme(
            activeColor = GraffuxColors.HotPink,
            focusColor = GraffuxColors.HotPink,
            defaultShape = AzButtonShape.NONE_SQUARE,
            translucentBackground = GraffuxColors.Black.copy(alpha = 0.85f),
        )
        azConfig(
            noMenu = true,
            packButtons = true,
            activeClassifiers = setOf(selectedBrush.name),
            // This app doesn't populate its own footer content, so there is nothing the library's
            // own "About" footer would show beyond the auto "?" item azAbout() below already
            // covers. (The rail item cut off below the window's visible area, further down in
            // this file's history, turned out to be the *window* running out of vertical room,
            // not this footer -- see the Window() call's own comment.)
            showFooter = false,
        )
        // Android's own MainActivity.kt calls azAbout(dedupeAbout = true) with every other
        // argument left at its default -- no appRepositoryUrl is set there either -- so this
        // mirrors that exactly. Previously shipped as `azAbout(aboutRailItem = false)`: the
        // library's default "?" rail item crashed on close (a Skia text-layout assertion inside
        // AutoSizeText, `TextStyle.setHeight` on a degenerate zero font-size candidate mid the
        // overlay's close transition -- see DESKTOP.md's writeup). Fixed upstream in
        // aznavrail-cmp 11.45 (commit 50c56cd, "Fix AutoSizeText crash on zero font-size
        // candidate") -- the version bumped to alongside this change -- so the auto "?" item is
        // safe to re-enable.
        azAbout(dedupeAbout = true)
        BuiltInBrushes.presets.forEach { preset ->
            azRailItem(
                id = "brush.${preset.name}",
                text = preset.name,
                content = brushIcon,
                classifiers = setOf(preset.name),
                onClick = { selectedBrush = preset },
            )
        }
        azRailItem(
            id = "action.undo",
            text = "Undo",
            content = undoIcon,
            disabled = !canvasState.canUndo,
            onClick = { canvasState.undo() },
        )
        azRailItem(
            id = "action.redo",
            text = "Redo",
            content = redoIcon,
            disabled = !canvasState.canRedo,
            onClick = { canvasState.redo() },
        )
        azRailItem(
            id = "action.clear",
            text = "Clear",
            content = clearIcon,
            disabled = canvasState.committed == null,
            onClick = { canvasState.clear() },
        )
        azRailItem(
            id = "action.save",
            text = "Save",
            content = saveIcon,
            disabled = canvasState.committed == null,
            onClick = { onSaved(canvasState.exportPng()?.absolutePath) },
        )
        // A rail item to reopen the tool-options panel once its own close button has been used --
        // otherwise, once dismissed, brush size/flow/colour would have no way back onscreen.
        azRailItem(
            id = "action.toolOptions",
            text = "Tool Options",
            content = brushIcon,
            onClick = { showToolOptions = true },
        )

        background(weight = 0) {
            // Android's own screens sit on `MaterialTheme.colorScheme.background` (black, per
            // `GraffuxDarkColorScheme`) -- without an explicit Surface here the window's content
            // pane defaults to plain white, the one background-color mismatch icons/accent/rail
            // theming alone didn't fix.
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Procreate-style floating, draggable panel in place of the old fixed top toolbar
                // Row -- matches Android's own `FloatingWindow`-based tool-option panels (both wrap
                // the same `AzWindow`/`AzWindowState` primitive; see FloatingWindow.kt).
                if (showToolOptions) {
                    FloatingWindow(
                        title = "Tool Options",
                        onDismiss = { showToolOptions = false },
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
                lastSavedPath?.let { path ->
                    Text(
                        text = "Saved to $path",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = Color(0xFF43A047),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                DesktopStampCanvas(
                    state = canvasState,
                    brush = selectedBrush,
                    brushRadiusPx = brushRadius,
                    colorArgb = selectedColor.toArgb(),
                    flow = brushFlow,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                )
            }
            }
        }
    }
}
