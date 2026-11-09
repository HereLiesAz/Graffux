package com.hereliesaz.graffux

import android.content.Context
import com.hereliesaz.graffitixr.feature.editor.prediction.GoogleInkGesturePredictor
import com.hereliesaz.graffitixr.feature.editor.prediction.PredictionTournament
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.hereliesaz.graffitixr.design.components.GraffuxAlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import com.hereliesaz.graffitixr.design.components.GraffuxChoiceChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape
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
 * Handedness controls which side the rails dock to; units feed the canvas rulers. Also shows the
 * build version. Values are read from and written straight through
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
    val storedPlacement by vm.propertiesPlacement.collectAsStateWithLifecycle()
    val smallestWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp
    val brushSizeFixedOnScreen by vm.brushSizeFixedOnScreen.collectAsStateWithLifecycle()
    val surfaceOutlineAssist by vm.surfaceOutlineAssist.collectAsStateWithLifecycle()
    val sampleRate by vm.inputSampleRateHz.collectAsStateWithLifecycle()
    val renderScale by vm.canvasRenderScale.collectAsStateWithLifecycle()
    val gestureMapping by vm.gestureMapping.collectAsStateWithLifecycle()
    var showNotices by remember { mutableStateOf(false) }
    var showDeveloper by remember { mutableStateOf(false) }
    var gpuTestRunning by remember { mutableStateOf(false) }
    var gpuTestResult by remember { mutableStateOf<GpuStampEngineSelfTest.Result?>(null) }
    val coroutineScope = rememberCoroutineScope()

    if (showNotices) {
        OpenSourceNotices(onDismiss = { showNotices = false })
    }
    gpuTestResult?.let { result ->
        GpuTestResultDialog(result, onDismiss = { gpuTestResult = null })
    }

    SettingsFrame(onClose = onClose, modifier = modifier) {
        SectionHeader(stringResource(R.string.settings_section_document), top = 8.dp)
        ActionRow(
            title = stringResource(R.string.settings_canvas_size),
            subtitle = stringResource(R.string.settings_canvas_size_hint),
            onClick = onDocumentSize,
        )
        HorizontalDivider()
        ActionRow(
            title = stringResource(R.string.settings_background),
            subtitle = stringResource(R.string.settings_background_hint),
            onClick = onBackground,
        )
        HorizontalDivider()
        SectionHeader(stringResource(R.string.settings_section_interface), top = 8.dp)
        SwitchRow(
            title = stringResource(R.string.settings_right_handed),
            subtitle = stringResource(R.string.settings_right_handed_hint),
            checked = rightHanded,
            onCheckedChange = vm::setRightHanded,
        )
        HorizontalDivider()
        SwitchRow(
            title = stringResource(R.string.settings_imperial),
            subtitle = stringResource(R.string.settings_imperial_hint),
            checked = imperial,
            onCheckedChange = vm::setImperialUnits,
        )
        HorizontalDivider()
        // Phones always show the design properties in the sheet, so the choice is offered only
        // where the side panel exists.
        if (smallestWidthDp >= PropertiesPlacement.TABLET_MIN_WIDTH_DP) {
            SwitchRow(
                title = stringResource(R.string.settings_side_panel),
                subtitle = stringResource(R.string.settings_side_panel_hint),
                checked = PropertiesPlacement.resolve(storedPlacement, smallestWidthDp) ==
                    PropertiesPlacement.SIDE_PANEL,
                onCheckedChange = { on ->
                    vm.setPropertiesPlacement(
                        (if (on) PropertiesPlacement.SIDE_PANEL else PropertiesPlacement.BOTTOM_SHEET).key,
                    )
                },
            )
            HorizontalDivider()
        }
        SwitchRow(
            title = stringResource(R.string.settings_brush_zoom),
            subtitle = stringResource(R.string.settings_brush_zoom_hint),
            checked = !brushSizeFixedOnScreen,
            onCheckedChange = { followsZoom -> vm.setBrushSizeFixedOnScreen(!followsZoom) },
        )
        HorizontalDivider()
        SwitchRow(
            title = stringResource(R.string.settings_outline_assist),
            subtitle = stringResource(R.string.settings_outline_assist_hint),
            checked = surfaceOutlineAssist,
            onCheckedChange = { vm.setSurfaceOutlineAssist(it) },
        )
        HorizontalDivider()

        SectionHeader(stringResource(R.string.settings_section_privacy), top = 16.dp)
        PrivacyConsentRows()
        StrokeDataRow(vm)
        HorizontalDivider()

        SectionHeader(stringResource(R.string.settings_section_performance), top = 16.dp)
        ChoiceRow(
            title = stringResource(R.string.settings_sample_rate),
            subtitle = stringResource(R.string.settings_sample_rate_hint),
            options = SAMPLE_RATES,
            selected = sampleRate,
            label = { hz ->
                if (hz <= 0) stringResource(R.string.settings_unlimited) else stringResource(R.string.settings_hz, hz)
            },
            onSelect = vm::setInputSampleRateHz,
        )
        HorizontalDivider()
        ChoiceRow(
            title = stringResource(R.string.settings_render_scale),
            subtitle = stringResource(R.string.settings_render_scale_hint),
            options = RENDER_SCALES,
            selected = renderScale,
            label = { scale -> stringResource(R.string.settings_percent, (scale * PERCENT).roundToInt()) },
            onSelect = vm::setCanvasRenderScale,
        )
        HorizontalDivider()

        SectionHeader(stringResource(R.string.settings_section_gestures), top = 16.dp)
        GestureSlot.entries.forEach { slot ->
            ChoiceRow(
                title = stringResource(slot.labelRes()),
                subtitle = stringResource(R.string.settings_gesture_off_hint),
                options = GestureAction.entries,
                selected = gestureMapping[slot] ?: slot.defaultAction,
                label = { stringResource(it.labelRes()) },
                onSelect = { action -> vm.setGestureAction(slot, action) },
            )
            HorizontalDivider()
        }

        SectionHeader(stringResource(R.string.settings_section_about), top = 16.dp)
        CheckForUpdatesRow()
        HorizontalDivider()
        ActionRow(
            title = stringResource(R.string.settings_notices),
            subtitle = stringResource(R.string.settings_notices_hint),
            onClick = { showNotices = true },
        )
        HorizontalDivider()

        // Diagnostics and experiments: folded away so a painter never has to read them.
        ActionRow(
            title = stringResource(
                if (showDeveloper) R.string.settings_developer_open else R.string.settings_developer_closed,
            ),
            subtitle = stringResource(R.string.settings_developer_hint),
            onClick = { showDeveloper = !showDeveloper },
        )
        if (showDeveloper) {
            ActionRow(
                title = stringResource(
                    if (gpuTestRunning) R.string.settings_gpu_testing else R.string.settings_gpu_test,
                ),
                subtitle = stringResource(R.string.settings_gpu_test_hint),
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
        }

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.settings_version, appVersion),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
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
        Text(stringResource(R.string.settings_reports_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(
                if (connected) R.string.settings_reports_connected else R.string.settings_reports_connect_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (connected) {
            TextButton(onClick = { vm.disconnectPredictionReports() }) {
            Text(stringResource(R.string.settings_reports_disconnect))
        }
        } else {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.settings_reports_token)) },
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
            ) { Text(stringResource(R.string.settings_reports_connect)) }
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
        title = stringResource(R.string.settings_stroke_data),
        subtitle = stringResource(R.string.settings_stroke_data_hint, pending),
        options = listOf(true, false),
        selected = recording,
        label = { stringResource(if (it) R.string.settings_on else R.string.settings_off) },
        onSelect = {
            recording = it
            prefs.edit().putBoolean(STROKE_DATA_KEY, it).apply()
        },
    )
    TextButton(
        onClick = { vm.uploadStrokeData(context) },
        enabled = recording && pending > 0,
    ) { Text(stringResource(R.string.settings_stroke_upload)) }
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
        title = stringResource(R.string.settings_predictors),
        subtitle = stringResource(R.string.settings_predictors_hint),
        options = listOf("") + PredictionTournament.MODEL_NAMES,
        selected = solo,
        label = { it.ifEmpty { stringResource(R.string.settings_predictors_all) } },
        onSelect = {
            solo = it
            prefs.edit().putString(PredictionTournament.SOLO_KEY, it).apply()
        },
    )
    var inkProfile by remember {
        mutableStateOf(prefs.getString(PredictionTournament.INK_PROFILE_KEY, null) ?: "standard")
    }
    ChoiceRow(
        title = stringResource(R.string.settings_ink_tuning),
        subtitle = stringResource(R.string.settings_ink_tuning_hint),
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
 * stored choice). There is no CPU renderer: a device where it can't start cannot draw.
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
        title = stringResource(R.string.settings_direct),
        subtitle = stringResource(R.string.settings_direct_hint),
        options = listOf(false, true),
        selected = direct,
        label = { stringResource(if (it) R.string.settings_on else R.string.settings_off) },
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
    GraffuxAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            GraffuxButton(
                text = stringResource(R.string.settings_close_button),
                onClick = onDismiss,
                shape = GraffuxButtonShape.RECTANGLE,
            )
        },
        title = { Text(stringResource(R.string.settings_notices)) },
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
    GraffuxAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            GraffuxButton(
                text = stringResource(R.string.settings_close_button),
                onClick = onDismiss,
                shape = GraffuxButtonShape.RECTANGLE,
            )
        },
        title = {
            Text(stringResource(if (result.success) R.string.settings_gpu_pass else R.string.settings_gpu_fail))
        },
        text = {
            Column {
                Text(result.message, style = MaterialTheme.typography.bodyMedium)
                result.bitmap?.let { bmp ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.settings_gpu_expect),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.settings_gpu_output),
                        modifier = Modifier.size(200.dp),
                    )
                }
            }
        },
    )
}

/** Percent, for showing a fraction. */
private const val PERCENT = 100

/** Offered sample rates. 0 means unthrottled — every reported touch sample is drawn. */
private val SAMPLE_RATES = listOf(30, 60, 90, 120, 0)

/** Offered canvas resolutions. Memory scales with the square, so 50% is a quarter of the bytes. */
private val RENDER_SCALES = listOf(1f, 0.75f, 0.5f, 0.25f)

/** A titled row of mutually exclusive choices, rendered as a wrapped strip of chips. */
@Suppress("FunctionNaming", "LongParameterList") // Composable naming, as everywhere else in this file.
@Composable
internal fun <T> ChoiceRow(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
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
                GraffuxChoiceChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = label(option),
                )
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun SwitchRow(
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

