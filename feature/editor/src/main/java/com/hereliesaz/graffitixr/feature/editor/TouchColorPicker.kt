@file:Suppress("FunctionNaming")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.hereliesaz.graffitixr.common.util.ColorHarmony
import com.hereliesaz.graffitixr.common.util.HarmonyMode
import com.hereliesaz.graffitixr.common.util.NamedPaletteBook
import com.hereliesaz.graffitixr.common.util.PaletteEdit
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
import com.hereliesaz.graffitixr.common.azphalt.PaletteToolContribution
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape
import com.hereliesaz.graffitixr.design.components.SurfaceKind
import com.hereliesaz.graffitixr.design.components.ToolSurface

/**
 * Hosts the Colour sheet and, behind its button, the wet-paint mixer.
 *
 * The five-page picker applies live to the brush, offers a primary/secondary swap, and uses the
 * existing digital swatch store. The physical mixer remains a separate surface and owns paint loads.
 */
@Suppress("LongParameterList")
@Composable
fun TouchColorPickerHost(
    show: Boolean,
    paletteTools: List<PaletteToolContribution>,
    currentColor: Color,
    secondaryColor: Color,
    onSelectColor: (Color) -> Unit,
    onSelectSecondaryColor: (Color) -> Unit,
    onSwapColors: () -> Unit,
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {},
    board: PaletteBoard? = null,
    mixes: List<SavedMix> = emptyList(),
    mixActions: MixActions = MixActions(),
    recentColors: List<Color> = emptyList(),
    onCommitColor: (Color) -> Unit = {},
    onDeleteRecentColor: (Color) -> Unit = {},
    savedPalette: List<Color> = emptyList(),
    onSavePaletteColor: (Color) -> Unit = {},
    onRemovePaletteColor: (Color) -> Unit = {},
    namedPalettes: NamedPaletteBook? = NamedPaletteBook(),
    onPaletteEdit: (PaletteEdit) -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(PickerPage.DISC) }
    val held = board ?: remember { newMixerBoard() }
    val wetTool = paletteTools.firstOrNull { it.kind == PaletteToolContribution.WET_PAINT }
    var wetMixer by remember { mutableStateOf(false) }
    if (!show) {
        wetMixer = false
        return
    }
    if (wetMixer && wetTool != null) {
        PaletteMixerDialog(
            board = held,
            currentColor = currentColor,
            secondaryColor = secondaryColor,
            onSelectColor = onSelectColor,
            onSelectSecondaryColor = onSelectSecondaryColor,
            onSwapColors = onSwapColors,
            onDismiss = { wetMixer = false },
            onChanged = onChanged,
            mixes = mixes,
            mixActions = mixActions,
        )
    } else {
        TouchColorPicker(
            currentColor = currentColor,
            onSelectColor = onSelectColor,
            onCommitColor = onCommitColor,
            recentColors = recentColors,
            onDeleteRecentColor = onDeleteRecentColor,
            onDismiss = onDismiss,
            onOpenMixer = if (wetTool != null) ({ wetMixer = true }) else null,
            mixerLabel = wetTool?.name,
            secondaryColor = secondaryColor,
            onSwap = onSwapColors,
            savedPalette = savedPalette,
            onSavePaletteColor = onSavePaletteColor,
            onRemovePaletteColor = onRemovePaletteColor,
            namedPalettes = namedPalettes,
            onPaletteEdit = onPaletteEdit,
            page = page,
            onPage = { page = it },
        )
    }
}

/** Modes mirror ProCraft's five-page picker; page is UI state, never paint state. */
enum class PickerPage(val label: String) {
    DISC("Disc"), CLASSIC("Classic"), HARMONY("Harmony"), VALUE("Value"), PALETTES("Palettes")
}

/**
 * ProCraft-style absolute colour controls. All RGB changes still go through the original
 * editor callbacks. HSV coordinates are held locally to preserve the chosen hue at grey
 * and both hue/saturation at black; a genuine external colour change resynchronizes them.
 */
@Suppress("LongParameterList")
@Composable
fun TouchColorPicker(
    currentColor: Color,
    onSelectColor: (Color) -> Unit,
    onCommitColor: (Color) -> Unit,
    recentColors: List<Color>,
    onDeleteRecentColor: (Color) -> Unit,
    onDismiss: () -> Unit,
    onOpenMixer: (() -> Unit)? = null,
    mixerLabel: String? = null,
    secondaryColor: Color? = null,
    onSwap: (() -> Unit)? = null,
    savedPalette: List<Color> = emptyList(),
    onSavePaletteColor: (Color) -> Unit = {},
    onRemovePaletteColor: (Color) -> Unit = {},
    page: PickerPage? = null,
    onPage: ((PickerPage) -> Unit)? = null,
    namedPalettes: NamedPaletteBook? = NamedPaletteBook(),
    onPaletteEdit: (PaletteEdit) -> Unit = {},
) {
    var localPage by rememberSaveable { mutableStateOf(PickerPage.DISC) }
    val activePage = page ?: localPage
    val selectPage: (PickerPage) -> Unit = { next -> localPage = next; onPage?.invoke(next) }
    var harmony by rememberSaveable { mutableStateOf(HarmonyMode.COMPLEMENTARY) }
    val updatedSelect by rememberUpdatedState(onSelectColor)
    val updatedCommit by rememberUpdatedState(onCommitColor)
    var hsv by remember { mutableStateOf(PickerHsv.from(currentColor)) }
    var lastApplied by remember { mutableStateOf(currentColor) }
    LaunchedEffect(currentColor) {
        if (lastApplied.toArgb() != currentColor.toArgb()) {
            hsv = PickerHsv.from(currentColor, hsv)
            lastApplied = currentColor
        }
    }
    val apply: (Color) -> Unit = {
        hsv = PickerHsv.from(it, hsv)
        lastApplied = it
        updatedSelect(it)
    }
    val applyHsv: (PickerHsv) -> Unit = {
        hsv = it
        lastApplied = it.color(currentColor.alpha)
        updatedSelect(lastApplied)
    }
    val settle: () -> Unit = { updatedCommit(lastApplied) }
    val pickSwatch: (Color) -> Unit = {
        apply(it.copy(alpha = currentColor.alpha))
        settle()
    }

    ToolSurface(
        kind = SurfaceKind.PICKER,
        title = stringResource(R.string.colour_sheet_title),
        onDismiss = onDismiss,
        clearOfRails = true,
        fixedFooter = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PickerPage.entries.forEach { mode ->
                    FilterChip(selected = activePage == mode, onClick = { selectPage(mode) },
                        label = { Text(mode.label) }, modifier = Modifier.testTag("touchColor.mode.${mode.name}"))
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                PickerSwatch(currentColor, "Current colour")
                if (secondaryColor != null && onSwap != null) {
                    TextButton(onClick = onSwap, modifier = Modifier.testTag("touchColor.swap")) { Text("Swap") }
                    PickerSwatch(secondaryColor, "Secondary colour")
                }
            }
            when (activePage) {
                PickerPage.DISC -> PickerDisc(hsv, null, applyHsv, settle)
                PickerPage.CLASSIC -> {
                    ColourPlane(hsv, applyHsv, settle)
                    HueStrip(hsv, applyHsv, settle)
                    PickerSlider("Saturation", hsv.saturation, { applyHsv(hsv.copy(saturation = it)) }, settle)
                    PickerSlider("Brightness", hsv.brightness, { applyHsv(hsv.copy(brightness = it)) }, settle)
                }
                PickerPage.HARMONY -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HarmonyMode.entries.forEach { kind ->
                            FilterChip(selected = kind == harmony, onClick = { harmony = kind }, label = { Text(kind.label) })
                        }
                    }
                    val partners = ColorHarmony.partners(hsv.hue, harmony)
                    PickerDisc(hsv, partners, applyHsv, settle)
                    PickerSlider("Brightness", hsv.brightness, { applyHsv(hsv.copy(brightness = it)) }, settle)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        partners.forEach { h ->
                            val color = hsv.copy(hue = h).color()
                            PickerSwatch(color, "Harmony ${hexOf(color)}", onPick = { pickSwatch(color) })
                        }
                    }
                }
                PickerPage.VALUE -> {
                    PickerNumericChannel("H", hsv.hue.roundToInt().coerceAtMost(359), 359,
                        { applyHsv(hsv.copy(hue = it.toFloat())) }, settle)
                    PickerNumericChannel("S", (hsv.saturation * 100).roundToInt(), 100,
                        { applyHsv(hsv.copy(saturation = it / 100f)) }, settle)
                    PickerNumericChannel("B", (hsv.brightness * 100).roundToInt(), 100,
                        { applyHsv(hsv.copy(brightness = it / 100f)) }, settle)
                    PickerNumericChannel("Red", (currentColor.red * 255).roundToInt(), 255,
                        { apply(currentColor.copy(red = it / 255f)) }, settle)
                    PickerNumericChannel("Green", (currentColor.green * 255).roundToInt(), 255,
                        { apply(currentColor.copy(green = it / 255f)) }, settle)
                    PickerNumericChannel("Blue", (currentColor.blue * 255).roundToInt(), 255,
                        { apply(currentColor.copy(blue = it / 255f)) }, settle)
                    HexColourField(currentColor, onApply = apply, onCommit = { updatedCommit(it) })
                }
                PickerPage.PALETTES -> {
                    NamedPalettePanel(namedPalettes, currentColor, pickSwatch, onPaletteEdit) {
                        SavedPickerSwatches(savedPalette, currentColor, pickSwatch,
                            onSavePaletteColor, onRemovePaletteColor, full = true)
                    }
                }
            }
            if (activePage != PickerPage.PALETTES) {
                RecentColorGrid(
                    colors = recentColors, selected = currentColor,
                    onPick = pickSwatch, onDelete = onDeleteRecentColor,
                )
                val activePalette = namedPalettes?.active
                if (activePalette != null) {
                    Text(activePalette.name, style = MaterialTheme.typography.labelMedium)
                    NamedPaletteSlots(activePalette, currentColor, pickSwatch, onPaletteEdit, cards = false)
                } else if (savedPalette.isNotEmpty()) {
                    SavedPickerSwatches(savedPalette, currentColor, pickSwatch,
                        onSavePaletteColor, onRemovePaletteColor, full = false)
                }
            }
            if (onOpenMixer != null) {
                GraffuxButton(
                    text = mixerLabel ?: stringResource(R.string.colour_mixer_default),
                    onClick = onOpenMixer,
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.testTag("touchColor.mixer"),
                )
            }
        }
    }
}

/** Numeric controls apply valid values live; the keyboard Done or focus loss commits. */
@Composable
private fun PickerNumericChannel(label: String, value: Int, max: Int, onChange: (Int) -> Unit, onCommit: () -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val commit by rememberUpdatedState(onCommit)
    val focus = LocalFocusManager.current
    LaunchedEffect(value) { if (!focused) text = value.toString() }
    DisposableEffect(Unit) { onDispose { if (edited) commit() } }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.length <= 3 && input.all { it.isDigit() }) {
                text = input
                input.toIntOrNull()?.takeIf { it in 0..max }?.let { onChange(it); edited = true }
            }
        },
        label = { Text("$label (0–$max)") }, singleLine = true,
        isError = text.isNotEmpty() && text.toIntOrNull()?.let { it !in 0..max } != false,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            if (focused && !state.isFocused) {
                if (edited) commit()
                edited = false
                text = latestValue.toString()
            }
            focused = state.isFocused
        }.testTag("touchColor.value.$label"),
    )
}

/** The existing saved swatch repository, not a second palette implementation. */
@Composable
private fun SavedPickerSwatches(colors: List<Color>, selected: Color, onPick: (Color) -> Unit,
    onSave: (Color) -> Unit, onRemove: (Color) -> Unit, full: Boolean) {
    Text("Saved palette", style = MaterialTheme.typography.labelMedium)
    if (full) TextButton(onClick = { onSave(selected.copy(alpha = 1f)) },
        modifier = Modifier.testTag("touchColor.palette.add")) { Text("Add current colour") }
    if (colors.isEmpty()) Text("No saved colours", style = MaterialTheme.typography.bodySmall)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = recentGridColumns(maxWidth)
        Column {
            colors.chunked(columns).forEach { row ->
                Row { row.forEach { color ->
                    PickerSwatch(color, "Saved ${hexOf(color)}", { onPick(color) },
                        if (full) ({ onRemove(color) }) else null)
                } }
            }
        }
    }
}

/** Swatch with a full-sized touch target; holding a saved swatch removes it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickerSwatch(color: Color, label: String, onPick: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    val interaction = if (onPick != null) Modifier.combinedClickable(onClick = onPick, onLongClick = onRemove) else Modifier
    Box(Modifier.size(48.dp).then(interaction).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).background(color, RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)))
    }
}
