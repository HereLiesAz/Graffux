package com.hereliesaz.graffux.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.ArgbColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

private const val WHEEL_SIZE_PX = 200

/**
 * A real HSV disc color picker for desktop — the same hue/saturation-by-angle-and-radius disc
 * `ColorPickerDialog`'s `ColorWheel` uses in `feature:editor` on Android
 * (`SketchToolsDialog.kt`), rebuilt against JVM-safe types instead of `android.graphics.Bitmap`/
 * `android.graphics.Color.HSVToColor` (neither exists on desktop JVM): the wheel raster is a
 * [BufferedImage] filled pixel-by-pixel via the shared, pure-Kotlin [ArgbColor] HSV<->RGB math
 * (`core:engine`'s `ArgbColor`, the same conversion Android's dab color resolution already uses),
 * not a hand-rolled second implementation of hue/saturation math.
 *
 * Not a literal port of the Android dialog (no harmony tab, no saved-palette/history rows) — just
 * the actual picking surface (disc + brightness slider), wired to a plain `onColorSelected`
 * callback, mirroring how this app's [PALETTE] swatches already work.
 */
@Composable
fun ColorWheel(
    currentColor: Color,
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialHsv = remember(Unit) {
        val argb = currentColor.toArgb()
        ArgbColor.rgbToHsv((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }

    // `currentColor` can change out from under this composable -- e.g. a palette swatch click in
    // Main.kt -- without the wheel itself being torn down/recreated, so hue/saturation/value must
    // resync to it. `lastSyncedColor` is the color this composable last either seeded/resynced from
    // *or itself emitted* via `onColorSelected`: every place below that calls `onColorSelected` also
    // updates it first, so when that same value round-trips back in as the next `currentColor`, the
    // equality check here sees no change and skips resetting -- otherwise a resync-on-every-change
    // would fight the user's own drag (each drag-driven onColorSelected -> currentColor update would
    // immediately reset hue/saturation/value from the recomputed RGB, which is lossy at the wheel's
    // rim and would visibly snap the thumb).
    var lastSyncedColor by remember { mutableStateOf(currentColor) }
    LaunchedEffect(currentColor) {
        if (currentColor != lastSyncedColor) {
            val argb = currentColor.toArgb()
            val hsv = ArgbColor.rgbToHsv((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
            hue = hsv[0]
            saturation = hsv[1]
            value = hsv[2]
            lastSyncedColor = currentColor
        }
    }

    // The 200x200 raster below is a real per-pixel HSV fill (40,000 trig-and-multiply pixels), not
    // free -- and the brightness Slider fires `onValueChange` continuously while dragging, once per
    // pointer-move, not just on release. Regenerating that raster synchronously on the composition
    // thread for every one of those callbacks (an adversarial-review finding, not something this
    // session's own manual testing had caught) is a real anti-pattern even if a single 200x200 pass
    // is cheap enough not to visibly jank on typical desktop hardware today: it doesn't scale if
    // WHEEL_SIZE_PX ever grows. Debounced and moved off the composition thread instead.
    var wheelBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(value) {
        delay(30)
        wheelBitmap = withContext(Dispatchers.Default) { generateWheelBitmap(value) }.toComposeImageBitmap()
    }

    fun pickFromOffset(offset: Offset, size: Int) {
        val center = size / 2f
        val dx = offset.x - center
        val dy = offset.y - center
        val radius = min(center, center)
        val dist = hypot(dx, dy).coerceAtMost(radius)
        saturation = (dist / radius).coerceIn(0f, 1f)
        val angleDeg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        hue = ((angleDeg + 360f) % 360f)
        val (r, g, b) = ArgbColor.hsvToRgb(hue, saturation, value)
        val picked = Color(red = r, green = g, blue = b)
        lastSyncedColor = picked
        onColorSelected(picked)
    }

    // The Box below is laid out at WHEEL_SIZE_PX *dp*, not px -- on any display with density != 1.0
    // (e.g. Windows 150% scaling) it renders at more/fewer actual pixels than that literal, while
    // `pointerInput`'s offsets are always reported in real on-screen pixels. `onSizeChanged` reports
    // that real rendered size, so hit-testing stays correct at any density instead of assuming a
    // fixed 1:1 dp-to-px mapping. Seeded to the density-correct px value up front (rather than 0) so
    // the very first click, before the first `onSizeChanged` callback, is still accurate.
    var wheelSizePx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    LaunchedEffect(density) {
        wheelSizePx = with(density) { WHEEL_SIZE_PX.dp.toPx() }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(WHEEL_SIZE_PX.dp)
                .onSizeChanged { wheelSizePx = it.width.toFloat() }
                .pointerInput(Unit) {
                    detectTapGestures { offset -> pickFromOffset(offset, wheelSizePx.toInt()) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> pickFromOffset(change.position, wheelSizePx.toInt()) }
                },
        ) {
            // Null only for the first ~30ms after this composable enters, before the debounced
            // effect above has produced its first raster -- the pointer-input area above is sized
            // and ready to receive input from the very first frame regardless.
            wheelBitmap?.let { bitmap ->
                Image(bitmap = bitmap, contentDescription = "Color wheel", modifier = Modifier.size(WHEEL_SIZE_PX.dp))
            }
        }
        Row {
            Text("Brightness")
            Slider(
                value = value,
                onValueChange = { newValue ->
                    value = newValue
                    val (r, g, b) = ArgbColor.hsvToRgb(hue, saturation, value)
                    val picked = Color(red = r, green = g, blue = b)
                    lastSyncedColor = picked
                    onColorSelected(picked)
                },
                valueRange = 0f..1f,
                modifier = Modifier.width(WHEEL_SIZE_PX.dp).height(32.dp),
            )
        }
    }
}

private fun generateWheelBitmap(value: Float): BufferedImage {
    val image = BufferedImage(WHEEL_SIZE_PX, WHEEL_SIZE_PX, BufferedImage.TYPE_INT_ARGB)
    val center = WHEEL_SIZE_PX / 2f
    val radius = center
    for (y in 0 until WHEEL_SIZE_PX) {
        for (x in 0 until WHEEL_SIZE_PX) {
            val dx = x - center
            val dy = y - center
            val dist = hypot(dx, dy)
            if (dist > radius) {
                image.setRGB(x, y, 0)
                continue
            }
            val saturation = (dist / radius).coerceIn(0f, 1f)
            val angleDeg = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f)
            val (r, g, b) = ArgbColor.hsvToRgb(angleDeg, saturation, value)
            image.setRGB(x, y, ArgbColor.argb(255, r, g, b))
        }
    }
    return image
}
