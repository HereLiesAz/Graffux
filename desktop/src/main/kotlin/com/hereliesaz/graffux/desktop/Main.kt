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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzAppMeta
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.LocalAzAppMeta
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import kotlin.math.roundToInt

/** A small, real palette — not exhaustive (Android's colour picker is a full HSV wheel this app
 *  doesn't have yet, see DESKTOP.md), but a genuine choice, not a hardcoded single colour. */
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
    Window(onCloseRequest = ::exitApplication, title = "Graffux") {
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
            // The library's fixed "About" footer is pinned below the rail's item list regardless
            // of how many items precede it; with 5 real items (4 presets + Undo) in this small
            // window it visually overlapped the last item and silently absorbed its clicks. This
            // app doesn't call azAbout() anyway, so there's nothing the footer would show.
            showFooter = false,
        )
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
                                    .clickable { selectedColor = color },
                            )
                        }
                    }
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
