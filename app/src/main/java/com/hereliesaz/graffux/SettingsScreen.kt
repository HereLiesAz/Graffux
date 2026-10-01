package com.hereliesaz.graffux

import android.content.Context
import com.hereliesaz.graffitixr.feature.editor.prediction.GoogleInkGesturePredictor
import com.hereliesaz.graffitixr.feature.editor.prediction.PredictionTournament
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.roundToInt
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.common.model.GestureAction
import com.hereliesaz.graffitixr.common.model.GestureSlot
import com.hereliesaz.graffitixr.feature.editor.strokedata.STROKE_DATA_KEY
import com.hereliesaz.graffitixr.feature.editor.strokedata.STROKE_DATA_PREFS
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngineSelfTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Graffux settings — the design-relevant preferences, shown as a full-bleed overlay over the editor.
 * Handedness controls which side the nav rail docks to; units feed the canvas rulers. Also offers a
 * tutorial reset and shows the build version. Values are read from and written straight through
 * [SettingsViewModel]; there's no local editing state to commit.
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    appVersion: String,
    onClose: () -> Unit,
    onDocumentSize: () -> Unit,
    onBackground: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)

    val rightHanded by vm.isRightHanded.collectAsStateWithLifecycle()
    val imperial by vm.isImperialUnits.collectAsStateWithLifecycle()
    val brushSizeFixedOnScreen by vm.brushSizeFixedOnScreen.collectAsStateWithLifecycle()
    val sampleRate by vm.inputSampleRateHz.collectAsStateWithLifecycle()
    val renderScale by vm.canvasRenderScale.collectAsStateWithLifecycle()
    val gestureMapping by vm.gestureMapping.collectAsStateWithLifecycle()
    var showNotices by remember { mutableStateOf(false) }
    var gpuTestRunning by remember { mutableStateOf(false) }
    var gpuTestResult by remember { mutableStateOf<GpuStampEngineSelfTest.Result?>(null) }
    val coroutineScope = rememberCoroutineScope()

    if (showNotices) {
        OpenSourceNotices(onDismiss = { showNotices = false })
    }
    gpuTestResult?.let { result ->
        GpuTestResultDialog(result, onDismiss = { gpuTestResult = null })
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(painterResource(GraffuxIcons.Close), contentDescription = "Close settings")
                }
            }
            Text(
                "Document",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            ActionRow(title = "Canvas Size", subtitle = "Set the document width and height.", onClick = onDocumentSize)
            HorizontalDivider()
            ActionRow(title = "Background", subtitle = "Choose the canvas background colour.", onClick = onBackground)
            HorizontalDivider()
            Text(
                "Interface",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            SwitchRow(
                title = "Right-handed",
                subtitle = "Docks the tool rail on the left; turn off for a left-side rail.",
                checked = rightHanded,
                onCheckedChange = vm::setRightHanded,
            )
            HorizontalDivider()
            SwitchRow(
                title = "Imperial units",
                subtitle = "Show ruler measurements in inches rather than centimetres.",
                checked = imperial,
                onCheckedChange = vm::setImperialUnits,
            )
            HorizontalDivider()
            SwitchRow(
                title = "Brush size follows zoom",
                subtitle = "Zooming in enlarges the brush on screen and zooming out shrinks it; " +
                    "the Size slider remains the base diameter.",
                checked = !brushSizeFixedOnScreen,
                onCheckedChange = { followsZoom -> vm.setBrushSizeFixedOnScreen(!followsZoom) },
            )
            HorizontalDivider()
            // No language picker. The only translations `:core:design` carried were GraffitiXR's —
            // its own `app_name`, its AR/Overlay/Mockup mode copy — and they have been removed from
            // this repository. Graffux ships one language, so a picker here would have offered
            // fourteen options that all resolve to the same English resources: the dead control this
            // audit keeps finding, rather than a feature waiting to work.
            //
            // `AppLanguage` and `SettingsRepository.language` stay where they are, in the shared
            // core modules GraffitiXR also consumes. Nothing in Graffux reads them.

            Text(
                "Performance",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            ChoiceRow(
                title = "Sample rate",
                subtitle = "Caps how often a stroke is redrawn. Every touch sample is always kept, " +
                    "and Brush never redraws faster than the screen refreshes, so Unlimited means " +
                    "once per displayed frame. A lower cap draws less power.",
                options = SAMPLE_RATES,
                selected = sampleRate,
                label = { hz -> if (hz <= 0) "Unlimited" else "$hz Hz" },
                onSelect = vm::setInputSampleRateHz,
            )
            HorizontalDivider()
            ChoiceRow(
                title = "Canvas resolution",
                subtitle = "Resolution new layers are created at, as a share of the screen. Each " +
                    "layer holds a full-size image, so halving this quarters the memory a layer " +
                    "costs — lower it if the app runs out of memory with several layers open. " +
                    "Existing layers keep the resolution they were made at.",
                options = RENDER_SCALES,
                selected = renderScale,
                label = { scale -> "${(scale * 100).roundToInt()}%" },
                onSelect = vm::setCanvasRenderScale,
            )
            HorizontalDivider()

            Text(
                "Gestures",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            GestureSlot.entries.forEach { slot ->
                ChoiceRow(
                    title = slot.label,
                    subtitle = "Off disables this gesture.",
                    options = GestureAction.entries,
                    selected = gestureMapping[slot] ?: slot.defaultAction,
                    label = { it.label },
                    onSelect = { action -> vm.setGestureAction(slot, action) },
                )
                HorizontalDivider()
            }

            Text(
                "Developer",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            ActionRow(
                title = if (gpuTestRunning) "Testing…" else "Test GPU Engine",
                subtitle = "Runs the native wgpu dab-stamping engine " +
                    "(docs/Native Rendering Engine Design.md) end to end on this " +
                    "device — init, stamp three overlapping dabs, read the result back — and " +
                    "shows what it produced. This is the same engine every live stroke already " +
                    "runs on when GPU stamping is active, so a failure here points to a real " +
                    "GPU/driver problem, not just a diagnostic no-op.",
                onClick = {
                    if (!gpuTestRunning) {
                        gpuTestRunning = true
                        coroutineScope.launch {
                            gpuTestResult = withContext(Dispatchers.Default) { GpuStampEngineSelfTest.run() }
                            gpuTestRunning = false
                        }
                    }
                },
            )
            HorizontalDivider()
            PredictionReportsRow(vm)
            HorizontalDivider()
            PredictionSoloRow()
            HorizontalDivider()
            GpuEngineRows()
            GpuTierRow()
            HorizontalDivider()

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { showNotices = true }) {
                Text("Open-source notices")
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Graffux $appVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * TEMPORARY. Paste a GitHub token so the editor files stroke-prediction rankings as issues on
 * HereLiesAz/Graffux (PredictionReportRepository). Stored encrypted on this device only.
 */
@Suppress("FunctionNaming") // Composable naming, as everywhere else in this file.
@Composable
private fun PredictionReportsRow(vm: SettingsViewModel) {
    val connected by vm.predictionReportsConnected.collectAsStateWithLifecycle()
    val status by vm.predictionReportsStatus.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("Prediction ranking reports", style = MaterialTheme.typography.bodyLarge)
        Text(
            if (connected) {
                "Filing rankings as GitHub issues on HereLiesAz/Graffux."
            } else {
                "Paste a fine-grained GitHub token (this repository only, Issues: read and write) " +
                    "to file stroke-prediction rankings as issues."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (connected) {
            TextButton(onClick = { vm.disconnectPredictionReports() }) { Text("Disconnect") }
        } else {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("GitHub token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            TextButton(
                onClick = {
                    vm.connectPredictionReports(token)
                    token = ""
                },
                enabled = token.isNotBlank(),
            ) { Text("Connect") }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Stroke-model training capture (feature/editor StrokeDataRecorder, tools/stroke-model): explicit
 * opt-in. Nothing is recorded or uploaded until the user enables it.
 */
@Suppress("FunctionNaming") // Composable naming, as everywhere else in this file.
@Composable
private fun StrokeDataRow(vm: SettingsViewModel) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(STROKE_DATA_PREFS, Context.MODE_PRIVATE) }
    var recording by remember { mutableStateOf(prefs.getBoolean(STROKE_DATA_KEY, false)) }
    val pending by vm.pendingStrokeData.count.collectAsStateWithLifecycle()
    // Refreshed on entry and on toggle here; the ViewModel refreshes again after every upload.
    LaunchedEffect(recording) { vm.pendingStrokeData.refresh(context) }
    ChoiceRow(
        title = "Contribute strokes to model training",
        subtitle = "Off by default. When enabled, Graffux records raw stroke input and motion " +
            "sensor context for its stroke model. With a connected GitHub token, pending files may " +
            "be uploaded for training. Turn this off to stop future collection and uploads. " +
            "$pending file(s) currently waiting on this device. Applies next time the canvas opens.",
        options = listOf(true, false),
        selected = recording,
        label = { if (it) "On" else "Off" },
        onSelect = {
            recording = it
            prefs.edit().putBoolean(STROKE_DATA_KEY, it).apply()
        },
    )
    TextButton(onClick = { vm.uploadStrokeData(context) }) { Text("Upload stroke data now") }
    HeatmapSettingsRow()
}

/**
 * TEMPORARY. Runs one stroke predictor alone (it draws the tail and is the only one ranked), or all
 * of them. Read by DrawingCanvas when the editor canvas is next composed.
 */
@Suppress("FunctionNaming") // Composable naming, as everywhere else in this file.
@Composable
private fun PredictionSoloRow() {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PredictionTournament.SOLO_PREFS, Context.MODE_PRIVATE)
    }
    var solo by remember { mutableStateOf(prefs.getString(PredictionTournament.SOLO_KEY, "").orEmpty()) }
    ChoiceRow(
        title = "Stroke predictors",
        subtitle = "Which predictors run while you draw. Pick one to test it alone; takes effect " +
            "next time the canvas opens.",
        options = listOf("") + PredictionTournament.MODEL_NAMES,
        selected = solo,
        label = { it.ifEmpty { "All" } },
        onSelect = {
            solo = it
            prefs.edit().putString(PredictionTournament.SOLO_KEY, it).apply()
        },
    )
    var inkProfile by remember {
        mutableStateOf(prefs.getString(PredictionTournament.INK_PROFILE_KEY, null) ?: "standard")
    }
    ChoiceRow(
        title = "Google Ink tuning",
        subtitle = "Standard, steadier (less overshoot, more lag) or more responsive (follows turns, " +
            "overshoots more). Reports name the tuning so they can be compared.",
        options = GoogleInkGesturePredictor.Profile.entries.map { it.label },
        selected = inkProfile,
        label = { it.replaceFirstChar(Char::uppercase) },
        onSelect = {
            inkProfile = it
            prefs.edit().putString(PredictionTournament.INK_PROFILE_KEY, it).apply()
        },
    )
}

/**
 * GPU engine settings. There is no engine choice: wgpu is the only GPU backend (the Vulkan and
 * OpenGL ES engines and their selector were retired; RetiredGpuBackendMigration drops the old
 * stored choice), and a device where it can't start draws on the CPU.
 */
@Suppress("FunctionNaming") // Composable naming, as everywhere else in this file.
@Composable
private fun GpuEngineRows() {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(GpuStampEngine.PREFS, Context.MODE_PRIVATE)
    }
    var direct by remember { mutableStateOf(GpuStampEngine.DirectSurface.enabled) }
    ChoiceRow(
        title = "Direct display",
        subtitle = "Draws the stroke in progress straight to the screen from the GPU engine, " +
            "skipping the app's own frame. Normal-blend layers with nothing visible above them; " +
            "everything else draws as before. Takes effect next time the canvas opens.",
        options = listOf(false, true),
        selected = direct,
        label = { if (it) "On" else "Off" },
        onSelect = {
            direct = it
            GpuStampEngine.DirectSurface.enabled = it
            prefs.edit().putBoolean(GpuStampEngine.DirectSurface.ENABLED_KEY, it).apply()
        },
    )
    MultipassRows(prefs)
    // No Jetpack Ink toggle: Ink's stock families are art utensils of their own in the brush list
    // (Ink Pen, Ink Marker, ...), not a setting that swaps the round Brush's renderer.
}

/**
 * Third-party attribution for the icon set.
 *
 * 88 of the 404 icons are Phosphor Icons drawings used as delivered, and Phosphor's MIT
 * licence asks for its notice to be included in copies of the software — which a file
 * sitting in the repository is not. The text is generated by `branding/icons/build.py`
 * straight from the icon manifest and written to `res/raw`, so it cannot drift from the set
 * that actually ships: add or drop a borrowed icon and this changes with it.
 */
@Composable
private fun OpenSourceNotices(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        context.resources.openRawResource(R.raw.notice_icons)
            .bufferedReader()
            .use { it.readText() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Open-source notices") },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
    )
}

/** Shows [GpuStampEngineSelfTest.run]'s outcome: the stamped bitmap on success, the failure
 *  reason (with a pointer to the `GpuStampEngine` logcat tag for the underlying error) on
 *  failure. */
@Composable
private fun GpuTestResultDialog(result: GpuStampEngineSelfTest.Result, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(if (result.success) "GPU engine: pass" else "GPU engine: fail") },
        text = {
            Column {
                Text(result.message, style = MaterialTheme.typography.bodyMedium)
                result.bitmap?.let { bmp ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Expect a soft red blob with two firmer, darker-edged lobes overlapping it — " +
                            "that's the hardness falloff and SRC_OVER build-up the stamp shader is responsible for.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "GPU stamp engine self-test output",
                        modifier = Modifier.size(200.dp),
                    )
                }
            }
        },
    )
}

/** Offered sample rates. 0 means unthrottled — every reported touch sample is drawn. */
private val SAMPLE_RATES = listOf(30, 60, 90, 120, 0)

/** Offered canvas resolutions. Memory scales with the square, so 50% is a quarter of the bytes. */
private val RENDER_SCALES = listOf(1f, 0.75f, 0.5f, 0.25f)

/** A titled row of mutually exclusive choices, rendered as a wrapped strip of chips. */
@Suppress("FunctionNaming") // Composable naming, as everywhere else in this file.
@Composable
internal fun <T> ChoiceRow(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Toggle from anywhere on the row (accessibility); the Switch just reflects state.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Suppress("FunctionNaming") // Composable naming, as everywhere else in this file.
@Composable
internal fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

