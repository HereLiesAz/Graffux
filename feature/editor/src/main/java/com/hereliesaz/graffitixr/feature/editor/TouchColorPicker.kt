@file:Suppress("FunctionNaming", "LongParameterList", "MagicNumber", "LongMethod", "CyclomaticComplexMethod")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
import com.hereliesaz.graffitixr.common.azphalt.PaletteToolContribution
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape
import com.hereliesaz.graffitixr.design.components.SurfaceKind
import com.hereliesaz.graffitixr.design.components.ToolSurface
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** A relative colour field with the exact selected colour at (0, 0). */
internal fun relativePickerColour(base: Color, x: Float, y: Float): Color {
    if (x == 0f && y == 0f) return base
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(base.toArgb(), it) }
    hsv[1] = (hsv[1] + x.coerceIn(-1f, 1f) * 0.5f).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] - y.coerceIn(-1f, 1f) * 0.5f).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor((base.alpha * 255f).roundToInt(), hsv))
}

/** How long a typed hex value rests before it counts as committed (so `#F0A` on the way to `#F0A0FF` is not kept). */
internal const val HEX_COMMIT_DELAY_MS = 700L

/**
 * A large direct-manipulation picker. Unlike a slider, every point on the field selects a colour.
 *
 * [secondaryColor], [onSelectSecondaryColor] and [onSwapColors] are only for the wet-paint mixer this host can
 * open, which has a background hand of its own; the Colour sheet itself edits the one brush colour.
 */
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
) {
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
        )
    }
}

/**
 * Finger-first saturation/value plane and hue rail; tap or drag anywhere, no tiny handles. Every move
 * applies live through [onSelectColor]; [onCommitColor] says when a colour is settled (a drag ended, a tap
 * landed, a valid hex rested, a recent colour was picked) so it can be remembered.
 */
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
) {
    val selected = currentColor
    val updatedSelected by rememberUpdatedState(selected)
    val updatedSelect by rememberUpdatedState(onSelectColor)
    val updatedCommit by rememberUpdatedState(onCommitColor)
    var hex by remember { mutableStateOf(hexOf(selected)) }
    // True once the field was typed in: only a typed value is committed by resting, never one the sheet filled in.
    var hexTyped by remember { mutableStateOf(false) }
    // The last colour this sheet applied, so a drag's end commits what the finger last chose even before
    // the new colour has come back down as [currentColor].
    var lastApplied by remember { mutableStateOf(selected) }
    val hexColour = hexFieldColor(hex, selected)
    val hexInvalid = hex.isNotBlank() && hexColour == null

    // Another path changed the colour (a recent swatch, a drag): the field shows it, unless it already
    // names that colour (typing "abc" must not turn into "#AABBCC" under the finger).
    LaunchedEffect(selected) {
        lastApplied = selected
        val typed = hexFieldColor(hex, selected)
        if (typed == null || typed.toArgb() != selected.toArgb()) {
            hex = hexOf(selected)
            hexTyped = false
        }
    }
    // A valid hex applies the moment it is valid, and counts as committed once it rests.
    LaunchedEffect(hexColour) {
        val colour = hexColour ?: return@LaunchedEffect
        if (!hexTyped) return@LaunchedEffect
        if (colour.toArgb() != updatedSelected.toArgb()) {
            lastApplied = colour
            updatedSelect(colour)
        }
        delay(HEX_COMMIT_DELAY_MS)
        updatedCommit(colour)
    }

    fun apply(color: Color) {
        lastApplied = color
        updatedSelect(color)
    }
    fun choosePlane(p: Offset, w: Float, h: Float, base: Color) {
        apply(relativePickerColour(base, 2f * p.x / w - 1f, 2f * p.y / h - 1f))
    }
    fun chooseHue(p: Offset, w: Float, base: Color) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(base.toArgb(), it) }
        hsv[0] = (hsv[0] + (2f * p.x / w - 1f).coerceIn(-1f, 1f) * 90f + 360f) % 360f
        apply(Color(android.graphics.Color.HSVToColor((base.alpha * 255f).roundToInt(), hsv)))
    }

    // clearOfRails: the left tool rail and the right layers rail draw over the screen's edges, so the sheet
    // sits between them.
    ToolSurface(kind = SurfaceKind.PICKER, title = "Colour", onDismiss = onDismiss, clearOfRails = true) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Centre is your current colour. Drag outward to adjust.", style = MaterialTheme.typography.labelMedium)
            Box(
                Modifier.size(280.dp)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            choosePlane(it, size.width.toFloat(), size.height.toFloat(), updatedSelected)
                            updatedCommit(lastApplied)
                        }
                    }
                    .pointerInput(Unit) {
                        var startColour = Color.White
                        detectDragGestures(
                            onDragStart = {
                                startColour = updatedSelected
                                choosePlane(it, size.width.toFloat(), size.height.toFloat(), startColour)
                            },
                            onDragEnd = { updatedCommit(lastApplied) },
                            onDragCancel = { updatedCommit(lastApplied) },
                            onDrag = { change, _ ->
                                choosePlane(change.position, size.width.toFloat(), size.height.toFloat(), startColour)
                                change.consume()
                            },
                        )
                    }
                    .semantics {
                        contentDescription = "Current colour at centre. Left less saturated, right more saturated, " +
                            "up brighter, down darker"
                    }
                    .testTag("touchColor.plane"),
            ) {
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                    val cells = 24
                    val tile = Size(size.width / cells, size.height / cells)
                    for (row in 0 until cells) for (col in 0 until cells) {
                        drawRect(relativePickerColour(selected,
                            (col + 0.5f) * 2f / cells - 1f,
                            (row + 0.5f) * 2f / cells - 1f),
                            topLeft = Offset(col * tile.width, row * tile.height), size = tile)
                    }
                    drawCircle(Color.Black, radius = 12.dp.toPx(), center = center)
                    drawCircle(Color.White, radius = 10.dp.toPx(), center = center,
                        style = Stroke(width = 3.dp.toPx()))
                    drawCircle(selected, radius = 7.dp.toPx(), center = center)
                }
            }
            Text("Hue: your current colour stays in the middle", style = MaterialTheme.typography.labelMedium)
            Box(
                Modifier.fillMaxWidth().height(52.dp)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            chooseHue(it, size.width.toFloat(), updatedSelected)
                            updatedCommit(lastApplied)
                        }
                    }
                    .pointerInput(Unit) {
                        var startColour = Color.White
                        detectDragGestures(
                            onDragStart = {
                                startColour = updatedSelected
                                chooseHue(it, size.width.toFloat(), startColour)
                            },
                            onDragEnd = { updatedCommit(lastApplied) },
                            onDragCancel = { updatedCommit(lastApplied) },
                            onDrag = { change, _ ->
                                chooseHue(change.position, size.width.toFloat(), startColour)
                                change.consume()
                            },
                        )
                    }
                    .semantics { contentDescription = "Current hue at centre, drag left or right to shift hue" }
                    .testTag("touchColor.hue"),
            ) {
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                    val stops = 48
                    val original = FloatArray(3).also {
                        android.graphics.Color.colorToHSV(selected.toArgb(), it)
                    }
                    for (i in 0 until stops) {
                        val shifted = original.copyOf()
                        shifted[0] = (original[0] + (2f * (i + 0.5f) / stops - 1f) * 90f + 360f) % 360f
                        drawRect(Color(android.graphics.Color.HSVToColor(shifted)),
                            topLeft = Offset(size.width * i / stops, 0f),
                            size = Size(size.width / stops, size.height))
                    }
                    drawCircle(Color.Black, radius = 14.dp.toPx(), center = center)
                    drawCircle(Color.White, radius = 11.dp.toPx(), center = center,
                        style = Stroke(width = 3.dp.toPx()))
                }
            }
            val invalidText = stringResource(R.string.colour_hex_invalid)
            OutlinedTextField(
                value = hex,
                onValueChange = {
                    hex = it
                    hexTyped = true
                },
                label = { Text(stringResource(R.string.colour_hex_label)) },
                isError = hexInvalid,
                supportingText = if (hexInvalid) ({ Text(invalidText) }) else null,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { if (hexInvalid) error(invalidText) }
                    .testTag("touchColor.hex"),
            )
            RecentColorGrid(
                colors = recentColors,
                selected = selected,
                onPick = { colour ->
                    // Keeps the brush's working alpha: a recent colour is remembered as its RGB.
                    val picked = colour.copy(alpha = selected.alpha)
                    apply(picked)
                    updatedCommit(picked)
                },
                onDelete = onDeleteRecentColor,
            )
            if (onOpenMixer != null) {
                GraffuxButton(text = mixerLabel ?: "Wet paint mixer", onClick = onOpenMixer,
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.testTag("touchColor.mixer"))
            }
        }
    }
}
