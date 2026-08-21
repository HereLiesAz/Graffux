package com.hereliesaz.graffux.hotreload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Graffux — Hot Reload Preview") {
        MaterialTheme {
            PreviewScreen()
        }
    }
}

@Composable
private fun PreviewScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var count by remember { mutableStateOf(0) }
            Text("Graffux Hot Reload Preview", style = MaterialTheme.typography.headlineSmall)
            Text("Edit this composable and save — the running window updates instantly.")
            Text("Clicks: $count")
            Button(onClick = { count++ }) {
                Text("Click me")
            }
        }
    }
}
