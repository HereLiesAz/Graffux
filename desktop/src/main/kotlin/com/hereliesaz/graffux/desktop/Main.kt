package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Entry point for the real Graffux desktop app (Linux + Windows) — see [DesktopStampCanvas] for
 * what it actually exercises (the shared azphalt engine + a tile-parallel, pen-aware renderer) and
 * DESKTOP.md at the repo root for what's verified vs. explicitly deferred in this first pass.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Graffux") {
        MaterialTheme {
            GraffuxDesktopApp()
        }
    }
}

@Composable
private fun GraffuxDesktopApp() {
    var brushRadius by remember { mutableFloatStateOf(24f) }
    var hardness by remember { mutableFloatStateOf(0.6f) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row {
                Column {
                    Text("Brush size: ${brushRadius.toInt()}px")
                    Slider(
                        value = brushRadius,
                        onValueChange = { brushRadius = it },
                        valueRange = 4f..96f,
                        modifier = Modifier.width(220.dp),
                    )
                }
                Spacer(modifier = Modifier.width(24.dp))
                Column {
                    Text("Hardness: ${(hardness * 100).toInt()}%")
                    Slider(
                        value = hardness,
                        onValueChange = { hardness = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.width(220.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            DesktopStampCanvas(
                brushRadiusPx = brushRadius,
                brushHardness = hardness,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
