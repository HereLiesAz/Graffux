package com.hereliesaz.graffux

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.feature.editor.strokedata.HeatmapCapture
import com.hereliesaz.graffitixr.feature.editor.strokedata.STROKE_DATA_PREFS
import com.hereliesaz.graffitixr.feature.editor.strokedata.STROKE_HEATMAP_KEY
import com.hereliesaz.graffitixr.feature.editor.strokedata.STROKE_HEATMAP_STATUS_KEY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Long enough for a person to answer the Magisk prompt. */
private const val HEATMAP_PROBE_TIMEOUT_MS = 30_000L

/**
 * Settings → Stroke data → Raw touch heatmap (root): off by default. Turning it on runs the helper
 * once through `su` right away, so the Magisk prompt appears here rather than mid-stroke, and shows
 * what it found (source and grid, or why not). A denial or failure turns the toggle back off.
 * Capture itself runs with the canvas (StrokeDataCapture) and stops when it leaves.
 */
@Suppress("FunctionNaming") // Composable naming, as in SettingsScreen.kt.
@Composable
internal fun HeatmapSettingsRow() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(STROKE_DATA_PREFS, Context.MODE_PRIVATE) }
    var on by remember { mutableStateOf(prefs.getBoolean(STROKE_HEATMAP_KEY, false)) }
    var status by remember { mutableStateOf(prefs.getString(STROKE_HEATMAP_STATUS_KEY, null).orEmpty()) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun select(want: Boolean) {
        if (checking || want == on) return
        if (!want) {
            on = false
            prefs.edit().putBoolean(STROKE_HEATMAP_KEY, false).apply()
            return
        }
        on = true
        checking = true
        status = "Asking for root…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val probe = HeatmapCapture.forContext(context)
                probe.start()
                probe.awaitSettled(HEATMAP_PROBE_TIMEOUT_MS).also { probe.stop() }
            }
            val ok = result.streaming
            val line = if (result.settled) result.describe() else "No answer from su within 30 s"
            status = if (ok) line else "Turned off. $line"
            on = ok
            checking = false
            prefs.edit().putBoolean(STROKE_HEATMAP_KEY, ok).putString(STROKE_HEATMAP_STATUS_KEY, status).apply()
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("Raw touch heatmap (root)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Rooted devices only. Also records the touch controller's raw capacitive image around " +
                "each stroke -- the real contact shape -- through a small read-only helper run with su. " +
                "Turning it on asks for root now. Applies next time the canvas opens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true, false).forEach { option ->
                FilterChip(
                    selected = option == on,
                    enabled = !checking,
                    onClick = { select(option) },
                    label = { Text(if (option) "On" else "Off") },
                )
            }
        }
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
