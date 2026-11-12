@file:Suppress("FunctionNaming", "LongParameterList", "MagicNumber")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val updatedForeground by rememberUpdatedState(onSelectColor)
    val updatedBackground by rememberUpdatedState(onSelectSecondaryColor)
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(selected.toArgb(), it) }
    var hex by remember(selected) { mutableStateOf("#%06X".format(selected.toArgb() and 0xFFFFFF)) }

    fun choose(h: Float, s: Float, v: Float) {
        val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 359.999f),
            s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
        if (editingBackground) updatedBackground(color) else updatedForeground(color)
    }
    fun choosePlane(p: Offset, w: Float, h: Float) {
        val now = FloatArray(3).also { android.graphics.Color.colorToHSV(updatedSelected.toArgb(), it) }
        choose(now[0], p.x.div(w).coerceIn(0f, 1f), 1f - p.y.div(h).coerceIn(0f, 1f))
    }
    fun chooseHue(p: Offset, w: Float) {
        val now = FloatArray(3).also { android.graphics.Color.colorToHSV(updatedSelected.toArgb(), it) }
        choose(p.x.div(w).coerceIn(0f, 1f) * 359.999f, now[1], now[2])
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
            Text("Drag to choose saturation and brightness", style = MaterialTheme.typography.labelMedium)
            Box(
                Modifier.size(280.dp)
                    .background(Color.hsv(hsv[0], 1f, 1f))
                    .pointerInput(Unit) {
                        detectTapGestures { choosePlane(it, size.width.toFloat(), size.height.toFloat()) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { choosePlane(it, size.width.toFloat(), size.height.toFloat()) },
                            onDrag = { change, _ ->
                                choosePlane(change.position, size.width.toFloat(), size.height.toFloat())
                                change.consume()
                            },
                        )
                    }
                    .semantics { contentDescription = "Saturation left to right, brightness bottom to top" }
                    .testTag("touchColor.plane"),
            ) {
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                    drawRect(brush = Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
                    drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    drawCircle(Color.Black, radius = 11.dp.toPx(),
                        center = Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height))
                    drawCircle(Color.White, radius = 9.dp.toPx(),
                        center = Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height),
                        style = Stroke(width = 3.dp.toPx()))
                }
            }
            Text("Drag to change hue", style = MaterialTheme.typography.labelMedium)
            Box(
                Modifier.fillMaxWidth().height(52.dp)
                    .pointerInput(Unit) { detectTapGestures { chooseHue(it, size.width.toFloat()) } }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { chooseHue(it, size.width.toFloat()) },
                            onDrag = { change, _ ->
                                chooseHue(change.position, size.width.toFloat())
                                change.consume()
                            },
                        )
                    }
                    .semantics { contentDescription = "Hue, drag horizontally" }
                    .testTag("touchColor.hue"),
            ) {
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                    drawRect(brush = Brush.horizontalGradient(
                        (0..6).map { Color.hsv(it * 60f, 1f, 1f) }))
                    val cx = hsv[0] / 360f * size.width
                    drawCircle(Color.Black, radius = 14.dp.toPx(),
                        center = Offset(cx, size.height / 2))
                    drawCircle(Color.White, radius = 11.dp.toPx(),
                        center = Offset(cx, size.height / 2),
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
