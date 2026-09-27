package com.hereliesaz.graffitixr.feature.editor.strokedata

import android.content.Context
import android.hardware.SensorManager
import android.util.DisplayMetrics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.data.strokedata.StrokeDataStore
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import org.json.JSONObject

/** SharedPreferences file/key for Settings → Record strokes for training (on unless turned off). */
const val STROKE_DATA_PREFS = "stroke_prediction"
const val STROKE_DATA_KEY = "record_strokes"
/** Settings → Raw touch heatmap (root): off unless turned on (and root was granted then). */
const val STROKE_HEATMAP_KEY = "record_heatmap"
/** The last status line Settings showed for the heatmap, kept for the next visit. */
const val STROKE_HEATMAP_STATUS_KEY = "record_heatmap_status"

/**
 * A [StrokeDataRecorder] for the editor canvas while it's on screen, or null when recording is off.
 * Sensors run only while the canvas is composed; the session file closes when it leaves.
 */
@Composable
fun rememberStrokeDataRecorder(uiState: EditorUiState): StrokeDataRecorder? {
    val context = LocalContext.current
    val view = LocalView.current
    val prefs = remember(context) { context.getSharedPreferences(STROKE_DATA_PREFS, Context.MODE_PRIVATE) }
    val enabled = remember(prefs) { prefs.getBoolean(STROKE_DATA_KEY, true) }
    if (!enabled) return null
    val latestState = rememberUpdatedState(uiState)
    // Root heatmap helper: runs only while the canvas records, stopped with it.
    val heatmap = remember(context) {
        if (prefs.getBoolean(STROKE_HEATMAP_KEY, false)) HeatmapCapture.forContext(context) else null
    }
    val recorder = remember(context, view) {
        val store = StrokeDataStore.get(context)
        StrokeDataRecorder(
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager,
            context = { canvasContext(latestState.value, view.display?.rotation ?: 0) },
            sink = { record -> runCatching { store.append(record) { sessionHeader(context, view, heatmap) } } },
        ).also { it.heatmap = heatmap }
    }
    DisposableEffect(recorder) {
        heatmap?.start()
        recorder.start()
        onDispose {
            recorder.stop()
            heatmap?.stop()
            runCatching { StrokeDataStore.get(context).closeSession() }
        }
    }
    return recorder
}

/** What the stroke was drawn with and under: brush, tool, camera, rendering path. */
private fun canvasContext(s: EditorUiState, displayRotation: Int): JSONObject = JSONObject()
    .put("tool", s.activeTool.name)
    .put("brush", s.activeBrushName ?: "")
    .put("brushSize", s.brushSize.toDouble())
    .put("brushOpacity", s.brushOpacity.toDouble())
    .put("stabilizer", s.stabilizerAlgorithm.name)
    .put("stabilizerLevel", s.stabilizerLevel)
    .put("zoom", s.viewportZoom.toDouble())
    .put("rotationDeg", s.viewportRotation.toDouble())
    .put("displayRotation", displayRotation)
    .put("sampleRateHz", s.inputSampleRateHz)
    .put("gpu", GpuStampEngine.Backend.preferred.label)

private fun sessionHeader(context: Context, view: android.view.View, heatmap: HeatmapSource?): JSONObject {
    val metrics: DisplayMetrics = context.resources.displayMetrics
    return StrokeDataStore.deviceHeader()
        .put("schema", StrokeDataRecorder.SCHEMA_VERSION)
        .put("startedAtMs", System.currentTimeMillis())
        .put("displayHz", (view.display?.refreshRate ?: 0f).toDouble())
        .put("widthPx", metrics.widthPixels)
        .put("heightPx", metrics.heightPixels)
        .put("xdpi", metrics.xdpi.toDouble())
        .put("ydpi", metrics.ydpi.toDouble())
        .put("density", metrics.density.toDouble())
        // v3: raw touch heatmap capture status at the session's first stroke (SCHEMA.md).
        .put("heatmap", (heatmap?.status() ?: HeatmapStatus(HeatmapStatus.OFF)).toJson())
        .put("appVersion", runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "")
}
