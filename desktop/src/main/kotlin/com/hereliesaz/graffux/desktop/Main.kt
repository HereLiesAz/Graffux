package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
    // rail items without repeating this (bumped from an earlier 900 while testing a 9th item -- see
    // DESKTOP.md on why that item, azAbout()'s auto "?" button, isn't wired in yet).
    val windowState = rememberWindowState(position = WindowPosition(Alignment.Center), size = DpSize(1000.dp, 1100.dp))
    Window(onCloseRequest = ::exitApplication, title = "Graffux", state = windowState) {
        MaterialTheme {
            CompositionLocalProvider(
                // Desktop has no OS-level app name/icon the way an Android manifest does, so this
                // is provided by hand (matches the pattern aznavrail's own CMP demo app uses).
                LocalAzAppMeta provides AzAppMeta(name = "Graffux", packageId = "com.hereliesaz.graffux"),
            ) {
                GraffuxDesktopApp()
            }
        }
    }
}

@Composable
private fun GraffuxDesktopApp() {
    val navController = rememberNavController()
    var brushRadius by remember { mutableFloatStateOf(24f) }
    var selectedBrush by remember { mutableStateOf<AzphaltBrush>(BuiltInBrushes.presets.first()) }
    var selectedColor by remember { mutableStateOf(PALETTE.first()) }
    val canvasState = remember { CanvasState() }
    var lastSavedPath by remember { mutableStateOf<String?>(null) }
    var showColorWheel by remember { mutableStateOf(false) }

    AzHostActivityLayout(navController = navController, initiallyExpanded = true) {
        azTheme(
            activeColor = Color(0xFF7C4DFF),
            focusColor = Color(0xFF7C4DFF),
            defaultShape = AzButtonShape.NONE_SQUARE,
            translucentBackground = Color.Black.copy(alpha = 0.85f),
        )
        azConfig(
            noMenu = true,
            packButtons = true,
            activeClassifiers = setOf(selectedBrush.name),
            // This app doesn't call azAbout(), so there is nothing the library's own "About"
            // footer would show. (The rail item cut off below the window's visible area, further
            // down in this file's history, turned out to be the *window* running out of vertical
            // room, not this footer -- see the Window() call's own comment.)
            showFooter = false,
        )
        // `aboutRailItem` defaults to true in the library's own AzAdvancedConfig -- the auto "?"
        // rail item is NOT something azAbout() opts into, it's on by default and has to be opted
        // OUT of. It's suppressed here rather than left in a half-wired state: see DESKTOP.md for
        // the crash this surfaced (a Skia text-layout assertion inside the library's own
        // AutoSizeText, during the About overlay's close transition) and why it isn't wired in yet.
        azAbout(aboutRailItem = false)
        BuiltInBrushes.presets.forEach { preset ->
            azRailItem(
                id = "brush.${preset.name}",
                text = preset.name,
                classifiers = setOf(preset.name),
                onClick = { selectedBrush = preset },
            )
        }
        azRailItem(
            id = "action.undo",
            text = "Undo",
            disabled = !canvasState.canUndo,
            onClick = { canvasState.undo() },
        )
        azRailItem(
            id = "action.redo",
            text = "Redo",
            disabled = !canvasState.canRedo,
            onClick = { canvasState.redo() },
        )
        azRailItem(
            id = "action.clear",
            text = "Clear",
            disabled = canvasState.committed == null,
            onClick = { canvasState.clear() },
        )
        azRailItem(
            id = "action.save",
            text = "Save",
            disabled = canvasState.committed == null,
            onClick = { lastSavedPath = canvasState.exportPng()?.absolutePath },
        )

        background(weight = 0) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Brush: ${selectedBrush.name} — ${brushRadius.toInt()}px")
                        Slider(
                            value = brushRadius,
                            onValueChange = { brushRadius = it },
                            valueRange = 4f..96f,
                            modifier = Modifier.width(220.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(24.dp))
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
                                        color = if (color == selectedColor) Color(0xFF7C4DFF) else Color.Gray,
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        selectedColor = color
                                        showColorWheel = false
                                    },
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        // The swatch row above is a fixed 8-colour palette, not the full HSV wheel
                        // Android's ColorPickerDialog offers -- this toggle opens a real one (see
                        // ColorWheel.kt) rather than leaving custom colour choice unreachable.
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(selectedColor)
                                .border(
                                    width = if (showColorWheel) 3.dp else 1.dp,
                                    color = if (showColorWheel) Color(0xFF7C4DFF) else Color.Gray,
                                    shape = CircleShape,
                                )
                                .clickable { showColorWheel = !showColorWheel },
                        )
                    }
                }
                if (showColorWheel) {
                    // The rail is a translucent overlay floating on top of this full-bleed content
                    // column, not an inset that shrinks it (nothing before this ever grew tall
                    // enough to reach the rail's own item region to notice) -- a left inset here
                    // clears the expanded rail's ~88dp width so the wheel doesn't render under it.
                    ColorWheel(
                        currentColor = selectedColor,
                        onColorSelected = { selectedColor = it },
                        modifier = Modifier.padding(start = 100.dp, end = 16.dp),
                    )
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
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                )
            }
        }
    }
}
