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
        onColorSelected(Color(red = r, green = g, blue = b))
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(WHEEL_SIZE_PX.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset -> pickFromOffset(offset, WHEEL_SIZE_PX) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> pickFromOffset(change.position, WHEEL_SIZE_PX) }
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
                    onColorSelected(Color(red = r, green = g, blue = b))
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
