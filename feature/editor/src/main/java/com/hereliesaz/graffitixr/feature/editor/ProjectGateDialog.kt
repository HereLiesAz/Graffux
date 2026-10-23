// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/ProjectGateDialog.kt
package com.hereliesaz.graffitixr.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.ProjectFile

/**
 * The project dialog: shown whenever there is no project to work in (first launch with no
 * projects) and for File > New. With no project open it is mandatory: taps outside are swallowed and
 * Back does what it does at the app's root (leave the app). Over an open project
 * ([ProjectGateState.cancellable]) it offers Cancel, and Back cancels, returning to that project.
 *
 * Open… opens the system file picker; backing out of the picker returns here. Create makes the
 * project under the typed name. GPU calibration runs in the background while this is open
 * (see gpu/CalibrationCoordinator); the busy state covers its capped tail after the tap.
 *
 * It also sets the project's canvas size. A document-type menu (Screen, Paper, Instagram, Facebook,
 * …, Custom) lists that type's templates as chips; under them the width, height, aspect ratio and
 * DPI are editable and kept consistent with each other (see [CanvasSize]). [onPickImages] opens the
 * host's image picker and [pickedImageCount] reports what it returned, which the host passes to the
 * ViewModel along with the size on Save.
 *
 * The card scrolls its body and keeps Load and Save pinned below it, and lifts above the keyboard,
 * so every field and both actions stay reachable.
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // Composable naming; one card of sections.
@Composable
fun ProjectGateDialog(
    state: ProjectGateState,
    onLoad: () -> Unit,
    onSave: (name: String, width: Int, height: Int) -> Unit,
    pickedImageCount: Int = 0,
    onPickImages: () -> Unit = {},
    imperial: Boolean = true,
    onCancel: () -> Unit = {},
) {
    if (state.cancellable) BackHandler(onBack = onCancel)
    var name by remember(state.defaultName) { mutableStateOf(state.defaultName) }
    var category by remember(state.defaultName) { mutableStateOf(CATEGORY_SCREEN) }
    val fields = remember(state.defaultName, state.defaultWidth, state.defaultHeight) {
        SizeFields(
            CanvasSize(
                state.defaultWidth,
                state.defaultHeight,
                CanvasSize.DEFAULT_DPI,
                if (imperial) LengthUnit.INCHES else LengthUnit.CENTIMETERS,
            ),
        )
    }
    val busy = state.busyLabel != null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            // Swallow every touch outside the card: tapping the scrim never dismisses.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
            .imePadding()
            .testTag(TAG_SCRIM),
        contentAlignment = Alignment.Center,
    ) {
        // Dressed like GraffuxAlertDialog: square, translucent surface, outline, no tonal tint.
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = SURFACE_ALPHA),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
            modifier = Modifier.padding(24.dp).widthIn(max = 480.dp).testTag(TAG_DIALOG),
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .testTag(TAG_BODY),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Project", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Project name") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag(TAG_NAME),
                    )
                    if (!busy) {
                        CanvasSetup(state, fields, category, onCategory = { category = it })
                        OutlinedButton(
                            onClick = onPickImages,
                            modifier = Modifier.fillMaxWidth().testTag(TAG_IMAGES),
                        ) {
                            Text(if (pickedImageCount > 0) "Images: $pickedImageCount — change…" else "Import images…")
                        }
                    }
                }
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(state.busyLabel.orEmpty(), modifier = Modifier.testTag(TAG_BUSY))
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        if (state.cancellable) {
                            AzButton(
                                text = "Cancel",
                                onClick = onCancel,
                                shape = AzButtonShape.RECTANGLE,
                                modifier = Modifier.testTag(TAG_CANCEL),
                            )
                        }
                        AzButton(
                            text = "Open…",
                            onClick = onLoad,
                            shape = AzButtonShape.RECTANGLE,
                            modifier = Modifier.testTag(TAG_LOAD),
                        )
                        AzButton(
                            text = "Create",
                            onClick = {
                                onSave(ProjectFile.sanitizeName(name), fields.size.widthPx, fields.size.heightPx)
                            },
                            shape = AzButtonShape.RECTANGLE,
                            modifier = Modifier.testTag(TAG_SAVE),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The editable canvas size: the [CanvasSize] plus the text of each field. A field keeps exactly what
 * was typed into it while the others are recomputed around it.
 */
private class SizeFields(initial: CanvasSize) {
    var size by mutableStateOf(initial)
    var width by mutableStateOf(initial.widthText())
    var height by mutableStateOf(initial.heightText())
    var ratio by mutableStateOf(initial.ratioText())

    fun refresh() {
        width = size.widthText()
        height = size.heightText()
        ratio = size.ratioText()
    }

    fun set(new: CanvasSize) {
        size = new
        refresh()
    }

    fun editWidth(text: String) {
        width = text
        CanvasSize.parseLength(text)?.let {
            size = size.withWidthLength(it)
            height = size.heightText()
            ratio = size.ratioText()
        }
    }

    fun editHeight(text: String) {
        height = text
        CanvasSize.parseLength(text)?.let {
            size = size.withHeightLength(it)
            width = size.widthText()
            ratio = size.ratioText()
        }
    }

    fun editRatio(text: String) {
        ratio = text
        CanvasSize.parseRatio(text)?.let {
            size = size.withRatio(it)
            height = size.heightText()
        }
    }
}

/** Document type, its templates, and the width / height / ratio / DPI fields. */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod") // Composable naming; one section.
@Composable
private fun CanvasSetup(
    state: ProjectGateState,
    fields: SizeFields,
    category: String,
    onCategory: (String) -> Unit,
) {
    val screen = DocumentTemplate("This screen", state.defaultWidth, state.defaultHeight, CanvasSize.DEFAULT_DPI)
    val categories = listOf(CATEGORY_SCREEN) + DOCUMENT_CATEGORIES.map { it.name } + CATEGORY_CUSTOM
    val templates = when (category) {
        CATEGORY_SCREEN -> listOf(screen)
        CATEGORY_CUSTOM -> emptyList()
        else -> DOCUMENT_CATEGORIES.firstOrNull { it.name == category }?.templates.orEmpty()
    }
    Text("Canvas", style = MaterialTheme.typography.titleSmall)
    DropdownField("Document type", category, categories, onCategory, TAG_CATEGORY)
    if (templates.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            templates.forEach { t ->
                FilterChip(
                    selected = fields.size.widthPx == t.widthPx && fields.size.heightPx == t.heightPx,
                    onClick = { fields.set(CanvasSize(t.widthPx, t.heightPx, t.dpi, fields.size.unit)) },
                    label = { Text("${t.name} ${t.widthPx}×${t.heightPx}") },
                    modifier = Modifier.testTag("$TAG_TEMPLATE_PREFIX${t.name}"),
                )
            }
        }
    }
    // Width, height, ratio, DPI and units are for people who need them: folded until asked for,
    // except under Custom, where they are the whole point.
    var showSize by remember(category) { mutableStateOf(category == CATEGORY_CUSTOM) }
    if (!showSize) {
        TextButton(onClick = { showSize = true }, modifier = Modifier.testTag(TAG_SIZE_OPTIONS)) {
            Text("Size options (${fields.size.widthPx}×${fields.size.heightPx} px)…")
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val unit = fields.size.unit.label
        LengthField("Width ($unit)", fields.width, fields::editWidth, TAG_WIDTH, Modifier.weight(1f))
        LengthField("Height ($unit)", fields.height, fields::editHeight, TAG_HEIGHT, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = fields.ratio,
            onValueChange = fields::editRatio,
            label = { Text("Aspect ratio") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag(TAG_RATIO),
        )
        DropdownField(
            "DPI",
            fields.size.dpi.toString(),
            DPI_OPTIONS.map { it.toString() },
            { fields.set(fields.size.withDpi(it.toInt())) },
            TAG_DPI,
            Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LengthUnit.entries.forEach { unit ->
            FilterChip(
                selected = fields.size.unit == unit,
                onClick = { fields.set(fields.size.withUnit(unit)) },
                label = { Text(if (unit == LengthUnit.INCHES) "Inches" else "Centimetres") },
                modifier = Modifier.testTag("$TAG_UNIT_PREFIX${unit.label}"),
            )
        }
    }
}

@Suppress("FunctionNaming") // Composable naming.
@Composable
private fun LengthField(label: String, value: String, onValue: (String) -> Unit, tag: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.testTag(tag),
    )
}

/** A labelled button that opens a menu of [options]; picking one calls [onSelect]. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongParameterList") // Composable naming; label/value/options/tag are all needed.
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text("$label: $value ▾", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { open = false; onSelect(option) },
                    modifier = Modifier.testTag("$tag.$option"),
                )
            }
        }
    }
}

const val TAG_SCRIM = "projectGate.scrim"
const val TAG_DIALOG = "projectGate.dialog"
const val TAG_BODY = "projectGate.body"
const val TAG_NAME = "projectGate.name"
const val TAG_LOAD = "projectGate.load"
const val TAG_SAVE = "projectGate.save"
const val TAG_CANCEL = "projectGate.cancel"
const val TAG_SIZE_OPTIONS = "projectGate.sizeOptions"
const val TAG_BUSY = "projectGate.busy"
const val TAG_IMAGES = "projectGate.images"
const val TAG_CATEGORY = "projectGate.category"
const val TAG_TEMPLATE_PREFIX = "projectGate.template."
const val TAG_WIDTH = "projectGate.width"
const val TAG_HEIGHT = "projectGate.height"
const val TAG_RATIO = "projectGate.ratio"
const val TAG_DPI = "projectGate.dpi"
const val TAG_UNIT_PREFIX = "projectGate.unit."
private const val SCRIM_ALPHA = 0.6f

/** GraffuxAlertDialog's (and FloatingWindow's) surface alpha. */
private const val SURFACE_ALPHA = 0.85f
