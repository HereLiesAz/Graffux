@file:Suppress("FunctionNaming", "LongParameterList", "MagicNumber", "LongMethod", "CyclomaticComplexMethod")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape
import com.hereliesaz.graffitixr.design.components.SurfaceKind
import com.hereliesaz.graffitixr.design.components.ToolSurface
import kotlin.math.roundToInt

/** A relative colour field with the exact selected colour at (0, 0). */
internal fun relativePickerColour(base: Color, x: Float, y: Float): Color {
    if (x == 0f && y == 0f) return base
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(base.toArgb(), it) }
    hsv[1] = (hsv[1] + x.coerceIn(-1f, 1f) * 0.5f).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] - y.coerceIn(-1f, 1f) * 0.5f).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor((base.alpha * 255f).roundToInt(), hsv))
}

/** A large direct-manipulation picker. Unlike a slider, every point on the field selects a colour. */
@Composable
fun TouchColorPickerHost(
    show: Boolean,
    canMixPaint: Boolean,
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
) {
    val held = board ?: remember { newMixerBoard() }
    var wetMixer by remember { mutableStateOf(false) }
    if (!show) {
        wetMixer = false
        return
    }
    if (wetMixer && canMixPaint) {
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
            secondaryColor = secondaryColor,
            onSelectColor = onSelectColor,
            onSelectSecondaryColor = onSelectSecondaryColor,
            onSwapColors = onSwapColors,
            onDismiss = onDismiss,
            onOpenMixer = if (canMixPaint) ({ wetMixer = true }) else null,
        )
    }
}

/** Finger-first saturation/value plane and hue rail; tap or drag anywhere, no tiny handles. */
@Composable
fun TouchColorPicker(
    currentColor: Color,
    secondaryColor: Color,
    onSelectColor: (Color) -> Unit,
    onSelectSecondaryColor: (Color) -> Unit,
    onSwapColors: () -> Unit,
    onDismiss: () -> Unit,
    onOpenMixer: (() -> Unit)? = null,
) {
    var editingBackground by remember { mutableStateOf(false) }
    val selected = if (editingBackground) secondaryColor else currentColor
    val updatedSelected by rememberUpdatedState(selected)
    val updatedEditingBackground by rememberUpdatedState(editingBackground)
    val updatedForeground by rememberUpdatedState(onSelectColor)
    val updatedBackground by rememberUpdatedState(onSelectSecondaryColor)
    var hex by remember(selected) { mutableStateOf("#%06X".format(selected.toArgb() and 0xFFFFFF)) }

    fun apply(color: Color) {
        if (updatedEditingBackground) updatedBackground(color) else updatedForeground(color)
    }
    fun choosePlane(p: Offset, w: Float, h: Float, base: Color) {
        apply(relativePickerColour(base, 2f * p.x / w - 1f, 2f * p.y / h - 1f))
    }
    fun chooseHue(p: Offset, w: Float, base: Color) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(base.toArgb(), it) }
        hsv[0] = (hsv[0] + (2f * p.x / w - 1f).coerceIn(-1f, 1f) * 90f + 360f) % 360f
        apply(Color(android.graphics.Color.HSVToColor((base.alpha * 255f).roundToInt(), hsv)))
    }

    ToolSurface(kind = SurfaceKind.PICKER, title = "Colour", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(currentColor, secondaryColor).forEachIndexed { i, color ->
                    Box(
                        Modifier.size(52.dp)
                            .background(color, CircleShape)
                            .border(if (editingBackground == (i == 1)) 3.dp else 1.dp,
                                MaterialTheme.colorScheme.onSurface, CircleShape)
                            .pointerInput(i) { detectTapGestures { editingBackground = i == 1 } }
                            .semantics { contentDescription = if (i == 0) "Edit foreground colour" else "Edit background colour" }
                            .testTag("touchColor.hand.$i"),
                    )
                }
                GraffuxButton(text = "Swap", onClick = onSwapColors, shape = GraffuxButtonShape.RECTANGLE)
            }
            Text("Centre is your current colour. Drag outward to adjust.", style = MaterialTheme.typography.labelMedium)
            Box(
                Modifier.size(280.dp)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            choosePlane(it, size.width.toFloat(), size.height.toFloat(), updatedSelected)
                        }
                    }
                    .pointerInput(Unit) {
                        var startColour = Color.White
                        detectDragGestures(
                            onDragStart = {
                                startColour = updatedSelected
                                choosePlane(it, size.width.toFloat(), size.height.toFloat(), startColour)
                            },
                            onDrag = { change, _ ->
                                choosePlane(change.position, size.width.toFloat(), size.height.toFloat(), startColour)
                                change.consume()
                            },
                        )
                    }
                    .semantics {
                        contentDescription = "Current colour at centre. Left less saturated, right more saturated, up brighter, down darker"
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
                        detectTapGestures { chooseHue(it, size.width.toFloat(), updatedSelected) }
                    }
                    .pointerInput(Unit) {
                        var startColour = Color.White
                        detectDragGestures(
                            onDragStart = {
                                startColour = updatedSelected
                                chooseHue(it, size.width.toFloat(), startColour)
                            },
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it },
                    label = { Text("Hex") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("touchColor.hex"),
                )
                GraffuxButton(text = "Apply", onClick = {
                    parseHexColor(hex)?.let { if (editingBackground) updatedBackground(it) else updatedForeground(it) }
                }, shape = GraffuxButtonShape.RECTANGLE)
            }
            if (onOpenMixer != null) {
                GraffuxButton(text = "Wet paint mixer", onClick = onOpenMixer,
                    shape = GraffuxButtonShape.RECTANGLE,
                    modifier = Modifier.testTag("touchColor.mixer"))
            }
        }
    }
}
