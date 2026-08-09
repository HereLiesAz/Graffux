// ~~~ FILE: ./feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/SketchToolsDialogs.kt ~~~
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap as AndroidBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.toArgb
import com.hereliesaz.graffitixr.common.util.ColorHarmony
import com.hereliesaz.graffitixr.common.util.HarmonyMode
import com.hereliesaz.graffitixr.design.components.FloatingWindow
import com.hereliesaz.graffitixr.design.theme.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

/** The colour picker's three faces, mirroring Procreate's Disc / Harmony / Palettes tabs. */
enum class ColorPickerMode(val label: String) {
    DISC("Disc"), HARMONY("Harmony"), PALETTES("Palettes")
}

/**
 * The colour picker. Three modes over one shared hue/saturation/brightness state, so switching tabs
 * never loses the colour you were building:
 *
 *  - **Disc** — the hue/saturation wheel with a brightness slider.
 *  - **Harmony** — the same wheel, with the classical partners of the current hue marked on it;
 *    tapping a marker adopts that hue at the current saturation and brightness.
 *  - **Palettes** — the saved swatches, with the working colour addable and any swatch removable.
 *
 * [savedPalette] and its callbacks are hoisted rather than read here so the picker stays a pure
 * composable over its inputs; persistence belongs to the view-model.
 */
@Composable
fun ColorPickerDialog(
    currentColor: Color,
    history: List<Color>,
    onSelectColor: (Color) -> Unit,
    onDismiss: () -> Unit,
    strings: AppStrings,
    savedPalette: List<Color> = emptyList(),
    onSavePaletteColor: (Color) -> Unit = {},
    onRemovePaletteColor: (Color) -> Unit = {},
) {
    // Decompose current color into HSV
    val initHsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (currentColor.red * 255).toInt(),
        (currentColor.green * 255).toInt(),
        (currentColor.blue * 255).toInt(),
        initHsv
    )

    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initHsv[2]) }

    val selectedColor = remember(hue, saturation, brightness) {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
        Color(argb).copy(alpha = currentColor.alpha)
    }

    var mode by remember { mutableStateOf(ColorPickerMode.DISC) }
    var harmony by remember { mutableStateOf(HarmonyMode.COMPLEMENTARY) }
    val partners = remember(hue, harmony, mode) {
        if (mode == ColorPickerMode.HARMONY) ColorHarmony.partners(hue, harmony) else emptyList()
    }

    FloatingWindow(title = "Color", onDismiss = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Color preview
            Box(
                Modifier
                    .size(48.dp)
                    .background(selectedColor, CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
            )

            Spacer(Modifier.height(12.dp))

            // Mode tabs. All three drive the same hue/saturation/brightness, so switching between
            // them never discards the colour being built.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorPickerMode.entries.forEach { m ->
                    PickerChip(text = m.label, selected = mode == m) { mode = m }
                }
            }

            Spacer(Modifier.height(12.dp))

            when (mode) {
                ColorPickerMode.DISC, ColorPickerMode.HARMONY -> {
                    ColorWheel(
                        hue = hue,
                        saturation = saturation,
                        brightness = brightness,
                        markerHues = partners,
                        modifier = Modifier.size(240.dp),
                        onHueSaturationChanged = { h, s ->
                            hue = h
                            saturation = s
                        }
                    )

                    if (mode == ColorPickerMode.HARMONY) {
                        Spacer(Modifier.height(10.dp))
                        // Wrapped: five relationship names don't fit one row on a phone.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HarmonyMode.entries.forEach { h ->
                                PickerChip(text = h.label, selected = harmony == h) { harmony = h }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tap a marker on the wheel to take that hue.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Brightness slider
                    Text(
                        strings.adj.brightness,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Slider(
                        value = brightness,
                        onValueChange = { brightness = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                ColorPickerMode.PALETTES -> {
                    PalettesTab(
                        savedPalette = savedPalette,
                        history = history,
                        working = selectedColor,
                        onPick = { picked ->
                            val hsv = FloatArray(3)
                            android.graphics.Color.RGBToHSV(
                                (picked.red * 255).toInt(),
                                (picked.green * 255).toInt(),
                                (picked.blue * 255).toInt(),
                                hsv,
                            )
                            hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                        },
                        onSave = { onSavePaletteColor(selectedColor) },
                        onRemove = onRemovePaletteColor,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            AzButton(
                text = strings.common.apply,
                onClick = { onSelectColor(selectedColor) },
                shape = AzButtonShape.RECTANGLE,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A small selectable label — the picker's mode and harmony choosers. */
@Composable
private fun PickerChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.White else Color.Gray,
        )
    }
}

/**
 * The saved swatches, plus the recent-colour row. Tapping a swatch loads it as the working colour
 * (it is not applied until Apply, so a mis-tap costs nothing); long-pressing a *saved* swatch
 * removes it. Recents aren't removable — they are a rolling view, not something the user curates.
 */
@Composable
private fun PalettesTab(
    savedPalette: List<Color>,
    history: List<Color>,
    working: Color,
    onPick: (Color) -> Unit,
    onSave: () -> Unit,
    onRemove: (Color) -> Unit,
) {
    Column(Modifier.width(240.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Saved", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            AzButton(text = "Save current", onClick = onSave, shape = AzButtonShape.RECTANGLE)
        }
        Spacer(Modifier.height(6.dp))
        if (savedPalette.isEmpty()) {
            Text(
                "No saved colours yet — Save current adds the one you're mixing.",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                savedPalette.forEach { swatch ->
                    Swatch(
                        color = swatch,
                        selected = swatch.toArgb() == working.toArgb(),
                        onClick = { onPick(swatch) },
                        onLongClick = { onRemove(swatch) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Long-press a swatch to remove it.",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text("Recent", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            history.forEach { swatch ->
                Swatch(color = swatch, selected = false, onClick = { onPick(swatch) })
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Swatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .size(32.dp)
            .background(color, RoundedCornerShape(6.dp))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else Color.White.copy(alpha = 0.35f),
                RoundedCornerShape(6.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    )
}

/** How far off a harmony marker a pick can land and still snap to it. */
private const val HARMONY_SNAP_DEGREES = 12f

@Composable
private fun ColorWheel(
    hue: Float,
    saturation: Float,
    brightness: Float,
    modifier: Modifier = Modifier,
    // Harmony partners of the current hue, drawn as secondary rings. Picking near one snaps to it
    // exactly, so a relationship you chose stays the relationship you get.
    markerHues: List<Float> = emptyList(),
    onHueSaturationChanged: (hue: Float, saturation: Float) -> Unit
) {
    var wheelSize by remember { mutableStateOf(IntSize.Zero) }
    var wheelBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // Regenerate the wheel bitmap when size or brightness changes
    LaunchedEffect(wheelSize, brightness) {
        val px = wheelSize.width
        if (px <= 0) return@LaunchedEffect
        wheelBitmap = generateColorWheelBitmap(px, brightness)
    }

    val selectorPos = remember(hue, saturation, wheelSize) {
        val radius = wheelSize.width / 2f
        val angle = Math.toRadians(hue.toDouble())
        Offset(
            x = (radius + cos(angle) * saturation * radius).toFloat(),
            y = (radius + sin(angle) * saturation * radius).toFloat()
        )
    }

    fun pickFromOffset(offset: Offset) {
        val radius = wheelSize.width / 2f
        if (radius <= 0f) return
        val dx = offset.x - radius
        val dy = offset.y - radius
        val dist = sqrt(dx * dx + dy * dy)
        val newSat = (dist / radius).coerceIn(0f, 1f)
        val rawHue = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360) % 360).toFloat()
        // Snap to a harmony marker when the pick lands close to one: the point of the Harmony tab
        // is the exact relationship, and a finger is nowhere near degree-accurate.
        val snapped = markerHues.minByOrNull { m ->
            val d = abs(m - rawHue)
            min(d, 360f - d)
        }?.takeIf { m ->
            val d = abs(m - rawHue)
            min(d, 360f - d) <= HARMONY_SNAP_DEGREES
        }
        onHueSaturationChanged(snapped ?: rawHue, newSat)
    }

    val currentHue by rememberUpdatedState(hue)
    val currentSaturation by rememberUpdatedState(saturation)
    Canvas(
        modifier = modifier
            .onSizeChanged { wheelSize = it }
            .pointerInput(wheelSize) {
                detectTapGestures { offset -> pickFromOffset(offset) }
            }
            .pointerInput(wheelSize) {
                detectDragGestures { _, dragAmount ->
                    val radius = wheelSize.width / 2f
                    if (radius <= 0f) return@detectDragGestures
                    val angle = Math.toRadians(currentHue.toDouble())
                    val curX = radius + cos(angle) * currentSaturation * radius
                    val curY = radius + sin(angle) * currentSaturation * radius
                    pickFromOffset(Offset((curX + dragAmount.x).toFloat(), (curY + dragAmount.y).toFloat()))
                }
            }
    ) {
        // Draw wheel bitmap
        wheelBitmap?.let { bmp ->
            drawImage(bmp)
        }

        // Harmony partners: same ring treatment as the selector but thinner, so the relationship
        // is visible at a glance without competing with the colour you are actually setting.
        markerHues.forEach { m ->
            val a = Math.toRadians(m.toDouble())
            val r = wheelSize.width / 2f
            val pos = Offset(
                x = (r + cos(a) * saturation * r).toFloat(),
                y = (r + sin(a) * saturation * r).toFloat(),
            )
            drawCircle(Color.Black, radius = 9f, center = pos, style = Stroke(width = 2f))
            drawCircle(Color.White, radius = 7f, center = pos, style = Stroke(width = 1.5f))
        }

        // Draw selector ring
        drawCircle(
            color = Color.Black,
            radius = 12f,
            center = selectorPos,
            style = Stroke(width = 3f)
        )
        drawCircle(
            color = Color.White,
            radius = 10f,
            center = selectorPos,
            style = Stroke(width = 2f)
        )
    }
}

private suspend fun generateColorWheelBitmap(size: Int, brightness: Float): ImageBitmap =
    withContext(Dispatchers.Default) {
        val pixels = IntArray(size * size)
        val center = size / 2f
        val radius = center
        val hsv = FloatArray(3).also { it[2] = brightness }

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = (x - center).toDouble()
                val dy = (y - center).toDouble()
                val dist = sqrt(dx * dx + dy * dy)
                pixels[y * size + x] = if (dist <= radius) {
                    val angle = (Math.toDegrees(atan2(dy, dx)) + 360) % 360
                    hsv[0] = angle.toFloat()
                    hsv[1] = (dist / radius).toFloat().coerceIn(0f, 1f)
                    android.graphics.Color.HSVToColor(hsv)
                } else {
                    0 // transparent outside circle
                }
            }
        }

        val bmp = AndroidBitmap.createBitmap(size, size, AndroidBitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        bmp.asImageBitmap()
    }

@Composable
fun SizePickerDialog(
    currentSize: Float,
    onSizeChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    strings: AppStrings
) {
    FloatingWindow(title = strings.editor.brushSize, onDismiss = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.height(100.dp)) {
                Box(Modifier.size((currentSize).dp).background(Color.White, CircleShape))
            }

            Slider(value = currentSize, onValueChange = onSizeChange, valueRange = 5f..150f)

            AzButton(text = strings.common.done, onClick = onDismiss, modifier = Modifier.padding(top = 16.dp), shape = AzButtonShape.RECTANGLE)
        }
    }
}
