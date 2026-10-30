@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
 * Entry point for the real Graffux desktop app (Linux + Windows) — see [DesktopStampCanvas] for
 * the shared-engine drawing surface and DESKTOP.md at the repo root for what's verified vs.
 * explicitly deferred. The rail is drawn with the same pill, button and menu shapes as the Android
 * app's (DesktopRail.kt); AzNavRail (`aznavrail-cmp`) supplies only the About reader. The
 * brush presets on the rail are [BuiltInBrushes.presets] — the same shared, pure-Kotlin brush
 * definitions the Android app ships with, not desktop-only stand-ins.
 */
fun main() = application {
    // The library's default 800x600 window ran out of vertical room for a 6th rail item (Undo +
    // Redo pushed the rail's total height past 600px, so Redo silently rendered off the bottom of
    // the window with no scroll/overflow indicator -- not a rail capacity bug, just too small a
    // window for how many tools this app now has). 1000x1100 leaves real headroom to keep adding
    // rail items without repeating this (bumped from an earlier 900 while testing a 9th item,
    // the About "?" button -- see DESKTOP.md).
    // Fits the screen it opens on: 1000x1100 was taller than a 1080p display, pushing the title bar
    // or the bottom of the canvas off-screen.
    val windowState = rememberWindowState(position = WindowPosition(Alignment.Center), size = initialWindowSize())
    // Hoisted here (rather than inside GraffuxDesktopApp, where every other piece of UI state
    // lives) purely so Window's own onKeyEvent below -- Ctrl+Z / Ctrl+Y, a desktop convention
    // Android's touch-only editor has no equivalent shortcut for -- can reach it directly. Same
    // reason `lastSavedPath` (Ctrl+S's own feedback label) lives here too.
    val canvasState = remember { CanvasState() }
    var lastSavedPath by remember { mutableStateOf<String?>(null) }
    // exportPng() can throw (unwritable directory, full disk) -- IOException from ImageIO.write
    // propagating out of this composable's key-event handler would crash the whole app, and the
    // Save button's onClick below is just as unguarded, so both wrap the call and surface the
    // failure here instead of letting it crash or silently vanish.
    var saveError by remember { mutableStateOf<String?>(null) }
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
                event.key == Key.S -> {
                    try {
                        lastSavedPath = canvasState.exportPng()?.absolutePath
                        saveError = null
                    } catch (e: Exception) {
                        lastSavedPath = null
                        saveError = e.message ?: "Failed to save image"
                    }
                    true
                }
                else -> false
            }
        },
    ) {
        GraffuxDesktopTheme {
            CompositionLocalProvider(
                // Desktop has no OS-level app name/icon the way an Android manifest does, so the
                // About reader is given them by hand.
                LocalAzAppMeta provides AzAppMeta(name = "Graffux", packageId = "com.hereliesaz.graffux"),
            ) {
                GraffuxDesktopApp(
                    canvasState = canvasState,
                    lastSavedPath = lastSavedPath,
                    saveError = saveError,
                    onSaved = { lastSavedPath = it; saveError = null },
                    onSaveError = { lastSavedPath = null; saveError = it },
                )
            }
        }
    }
}

@Composable
private fun GraffuxDesktopApp(
    canvasState: CanvasState,
    lastSavedPath: String?,
    saveError: String?,
    onSaved: (String?) -> Unit,
    onSaveError: (String?) -> Unit,
) {
    var brushRadius by remember { mutableFloatStateOf(24f) }
    var brushFlow by remember { mutableFloatStateOf(1f) }
    var selectedBrush by remember { mutableStateOf<AzphaltBrush>(BuiltInBrushes.presets.first()) }
    var selectedColor by remember { mutableStateOf(PALETTE.first()) }
    var showColorWheel by remember { mutableStateOf(false) }
    var showToolOptions by remember { mutableStateOf(true) }
    // Multipass drying (experimental, GPU canvas only): off by default.
    var multipass by remember { mutableStateOf(MultipassSettings()) }
    // Real icons (the same master SVGs Android's `GraffuxIcons` generates from) -- see Icons.kt.
    val brushIcon = GraffuxDesktopIcons.brush()
    val undoIcon = GraffuxDesktopIcons.undo()
    val redoIcon = GraffuxDesktopIcons.redo()
    val clearIcon = GraffuxDesktopIcons.clear()
    val saveIcon = GraffuxDesktopIcons.save()

    // Which brush set's list is open beside the rail, and whether About is showing.
    var openSet by remember { mutableStateOf<String?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
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
                    Text("Colour")
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
                    title = "Colour",
                    onDismiss = { showColorWheel = false },
                    initialOffset = Offset(120f, 340f),
                ) {
                    ColorWheel(
                        currentColor = selectedColor,
                        onColorSelected = { selectedColor = it },
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // The save result floats over the canvas and goes away, instead of a text row above
            // it that pushed the canvas down for good.
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalMultipassSettings provides multipass) {
                    DesktopStampCanvas(
                        state = canvasState,
                        brush = selectedBrush,
                        brushRadiusPx = brushRadius,
                        colorArgb = selectedColor.toArgb(),
                        flow = brushFlow,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    )
                }
                SaveResultToast(
                    savedPath = lastSavedPath,
                    error = saveError,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                )
            }
        }
        }
        // The rail: one button per bundled brush set (its list opens beside it), then the canvas
        // actions, each with the key that does the same thing in its corner.
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
                RailButton("Undo", { canvasState.undo() }, icon = undoIcon, enabled = canvasState.canUndo, shortcut = "^Z")
                RailButton("Redo", { canvasState.redo() }, icon = redoIcon, enabled = canvasState.canRedo, shortcut = "⇧^Z")
                RailButton("Clear", { canvasState.clear() }, icon = clearIcon, enabled = canvasState.committed != null)
                RailButton(
                    "Save",
                    {
                        try {
                            onSaved(canvasState.exportPng()?.absolutePath)
                        } catch (e: Exception) {
                            onSaveError(e.message ?: "Failed to save image")
                        }
                    },
                    icon = saveIcon,
                    enabled = canvasState.committed != null,
                    shortcut = "^S",
                )
                // Reopens the tool-options panel once its own close button has been used.
                RailButton("Tool Options", { showToolOptions = true }, icon = brushIcon, active = showToolOptions)
                RailDivider()
                RailButton("About", { showAbout = true }, text = "?")
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
        if (showAbout) AboutReader(onDismiss = { showAbout = false })
    }
}

/** AzNavRail's own About reader for this repository. */
@Composable
private fun AboutReader(onDismiss: () -> Unit) {
    AzAboutPage(onDismiss = onDismiss, repoUrl = "https://github.com/HereLiesAz/Graffux")
}

/** The window's first size: up to 1280x1000dp, never more than 90% of the screen it opens on. */
private fun initialWindowSize(): DpSize {
    val screen = runCatching { java.awt.Toolkit.getDefaultToolkit().screenSize }.getOrNull()
    val w = screen?.width?.let { (it * SCREEN_FRACTION).toInt() } ?: MAX_WINDOW_W
    val h = screen?.height?.let { (it * SCREEN_FRACTION).toInt() } ?: MAX_WINDOW_H
    return DpSize(minOf(w, MAX_WINDOW_W).dp, minOf(h, MAX_WINDOW_H).dp)
}

private const val SCREEN_FRACTION = 0.9
private const val MAX_WINDOW_W = 1280
private const val MAX_WINDOW_H = 1000
private const val SAVE_TOAST_MS = 4_000L

/** Ctrl+S's result over the canvas for a few seconds: where the PNG went, or why it didn't. */
@Composable
private fun SaveResultToast(savedPath: String?, error: String?, modifier: Modifier = Modifier) {
    val text = when {
        error != null -> "Save failed: $error"
        savedPath != null -> "Saved to $savedPath"
        else -> null
    }
    var shown by remember(text) { mutableStateOf(text != null) }
    LaunchedEffect(text) {
        if (text != null) {
            kotlinx.coroutines.delay(SAVE_TOAST_MS)
            shown = false
        }
    }
    if (shown && text != null) {
        Text(
            text = text,
            color = if (error != null) GraffuxColors.Amber else GraffuxColors.White,
            modifier = modifier
                .background(Color(0xE6161618), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
