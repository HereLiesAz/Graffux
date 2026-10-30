// ~~~ FILE: ./feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/SketchToolsDialogs.kt ~~~
package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.material3.MaterialTheme
import android.graphics.Bitmap as AndroidBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.design.components.GraffuxButtonShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
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
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun ColorPickerDialog(
    currentColor: Color,
    secondaryColor: Color = Color.Black,
    history: List<Color>,
    onSelectColor: (Color) -> Unit,
    onSelectSecondaryColor: (Color) -> Unit = {},
    onSwapColors: () -> Unit = {},
    onDismiss: () -> Unit,
    strings: AppStrings,
    savedPalette: List<Color> = emptyList(),
    onSavePaletteColor: (Color) -> Unit = {},
    onRemovePaletteColor: (Color) -> Unit = {},
    /** Closing, with the colour the Foreground/Background target ended on (for the Recent row). */
    onDismissWithColor: ((Color) -> Unit)? = null,
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
    var target by remember { mutableStateOf(BrushColorTarget.FOREGROUND) }
    var alpha by remember { mutableFloatStateOf(currentColor.alpha) }

    fun loadWorking(color: Color) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt(),
            hsv,
        )
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        alpha = color.alpha
    }

    val selectedColor = remember(hue, saturation, brightness, alpha) {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
        Color(argb).copy(alpha = alpha)
    }
    // No separate Apply step: every change to the working colour — wheel drag, brightness slider,
    // a tapped palette/recent swatch — lands on the brush immediately. `target` is a key too, not
    // just `selectedColor`, so switching Foreground/Background re-applies even when the two
    // happen to already hold the same colour (selectedColor alone wouldn't change, and the effect
    // wouldn't rerun to push it to the newly-selected target).
    LaunchedEffect(selectedColor, target) {
        if (target == BrushColorTarget.FOREGROUND) onSelectColor(selectedColor)
        else onSelectSecondaryColor(selectedColor)
    }

    var mode by remember { mutableStateOf(ColorPickerMode.DISC) }
    var harmony by remember { mutableStateOf(HarmonyMode.COMPLEMENTARY) }
    val partners = remember(hue, harmony, mode) {
        if (mode == ColorPickerMode.HARMONY) ColorHarmony.partners(hue, harmony) else emptyList()
    }

    FloatingWindow(
        title = "Colour",
        onDismiss = { onDismissWithColor?.invoke(selectedColor) ?: onDismiss() },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BrushColorTarget.entries.forEach { candidate ->
                    val swatch = if (candidate == BrushColorTarget.FOREGROUND) currentColor else secondaryColor
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(swatch, CircleShape)
                            .border(
                                if (candidate == target) 3.dp else 1.dp,
                                if (candidate == target) Color.White else Color.White.copy(alpha = 0.4f),
                                CircleShape,
                            )
                            // Named and stateful for TalkBack: which colour this is, and whether
                            // it's the one the picker is editing.
                            .selectable(
                                selected = candidate == target,
                                role = Role.RadioButton,
                            ) {
                                target = candidate
                                loadWorking(swatch)
                            }
                            .semantics { contentDescription = "${candidate.label} colour ${hexOf(swatch)}" }
                            .testTag("color.target.${candidate.name.lowercase()}")
                    )
                }
                GraffuxButton(
                    text = "Swap",
                    onClick = {
                        val next = if (target == BrushColorTarget.FOREGROUND) secondaryColor else currentColor
                        onSwapColors()
                        loadWorking(next)
                    },
                    shape = GraffuxButtonShape.RECTANGLE,
                )
            }

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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Brightness slider
                    Text(
                        strings.adj.brightness,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Slider(
                        value = brightness,
                        onValueChange = { brightness = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = strings.adj.brightness }
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
                            hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]; alpha = picked.alpha
                        },
                        onSave = { onSavePaletteColor(selectedColor) },
                        onRemove = onRemovePaletteColor,
                    )
                }
            }

            // Opacity and an exact value, under every mode: the wheel alone is drag-only.
            Text(
                "Opacity ${(alpha * PERCENT).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start).padding(top = 8.dp),
            )
            Slider(
                value = alpha,
                onValueChange = { alpha = it },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Opacity" }.testTag("color.alpha"),
            )
            HexField(selectedColor) { loadWorking(it) }

        }
    }
}

private enum class BrushColorTarget(val label: String) { FOREGROUND("Foreground"), BACKGROUND("Background") }

/** The working colour typed as hex; applied on Done or as soon as a whole value is typed. */
@Suppress("FunctionNaming")
@Composable
private fun HexField(color: Color, onColor: (Color) -> Unit) {
    var text by remember { mutableStateOf(hexOf(color)) }
    var editing by remember { mutableStateOf(false) }
    // Follow the wheel, a swatch or the other target: any colour change that isn't the typed
    // value ends the edit, so a later Done can never re-apply a stale typed value.
    LaunchedEffect(color) {
        val (synced, stillEditing) = hexFieldSync(text, editing, color)
        text = synced
        editing = stillEditing
    }
    val valid = parseHexColor(text) != null
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            editing = true
            text = typed.take(HEX_FIELD_MAX)
            parseHexColor(text)?.let(onColor)
        },
        singleLine = true,
        label = { Text("Hex") },
        isError = !valid,
        supportingText = if (valid) null else ({ Text("#RRGGBB or #AARRGGBB") }),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
            editing = false
            parseHexColor(text)?.let(onColor)
        }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) editing = false }
            .testTag("color.hex"),
    )
}

/**
 * The hex field's text and editing state after the working colour became [color]: while the typed
 * [text] is that colour the edit goes on; any other colour (wheel, swatch, the other target) ends
 * it and shows [color].
 */
internal fun hexFieldSync(text: String, editing: Boolean, color: Color): Pair<String, Boolean> =
    if (editing && parseHexColor(text)?.toArgb() == color.toArgb()) text to true else hexOf(color) to false

/** A small selectable label — the picker's mode and harmony choosers. */
@Composable
private fun PickerChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            // A 48dp target around the drawn chip.
            .minimumInteractiveComponentSize()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                },
                RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The saved swatches, plus the recent-colour row. Tapping a swatch loads it as the working colour
 * — which, like every other change in this picker, lands on the brush immediately, no Apply step
 * — undo is one tap away in the history stack if that was a mis-tap; long-pressing a *saved*
 * swatch removes it. Recents aren't removable — they are a rolling view, not something the user
 * curates.
 */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod")
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
            Text(
                "Saved",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GraffuxButton(text = "Save current", onClick = onSave, shape = GraffuxButtonShape.RECTANGLE)
        }
        Spacer(Modifier.height(6.dp))
        if (savedPalette.isEmpty()) {
            Text(
                "No saved colours yet — Save current adds the one you're mixing.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text("Recent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            history.forEach { swatch ->
                Swatch(color = swatch, selected = false, onClick = { onPick(swatch) })
            }
            if (history.isEmpty()) {
                Text(
                    "Colours you close the picker on appear here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    // A 48dp target around the 36dp swatch.
    Box(
        Modifier
            .size(48.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Remove" else null,
                role = Role.Button,
            )
            .semantics {
                contentDescription = hexOf(color)
                this.selected = selected
            }
            .padding(6.dp)
            .background(color, RoundedCornerShape(6.dp))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else Color.White.copy(alpha = 0.35f),
                RoundedCornerShape(6.dp),
            )
    )
}

private const val PERCENT = 100f

/** `#AARRGGBB` is the longest value the hex field takes. */
private const val HEX_FIELD_MAX = 9

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
