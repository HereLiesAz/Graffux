@file:Suppress("FunctionNaming")
// SPDX-License-Identifier: Apache-2.0
// ProCraft-inspired absolute colour controls; source and attribution in docs/ProCraft adoption.md.
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.ArgbColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** HSB coordinates retained independently of RGB while dragging across black or grey. */
internal data class PickerHsv(val hue: Float, val saturation: Float, val brightness: Float) {
    fun color(alpha: Float = 1f): Color {
        val (r, g, b) = ArgbColor.hsvToRgb(hue % 360f, saturation, brightness)
        return Color(ArgbColor.argb((alpha * 255f).roundToInt(), r, g, b))
    }

    companion object {
        fun from(color: Color, previous: PickerHsv? = null): PickerHsv {
            val argb = color.toArgb()
            val (h, s, v) = ArgbColor.rgbToHsv(ArgbColor.red(argb), ArgbColor.green(argb), ArgbColor.blue(argb))
            return PickerHsv(if (s == 0f) previous?.hue ?: h else h,
                if (v == 0f) previous?.saturation ?: s else s, v)
        }
    }
}

internal val PickerPlaneMax: Dp = 280.dp
internal val PickerHueHeight: Dp = 52.dp
internal fun pickerPlaneSize(available: Dp): Dp = minOf(available, PickerPlaneMax).coerceAtLeast(0.dp)

private const val DISC_RADIUS = 0.58f
private const val RING_MID = 0.82f
private const val RING_WIDTH = 0.20f
private const val PICTURE_SIDE = 160

/** A single captured pointer owns a gesture; crossing the ring/disc boundary never changes its mode. */
private fun Modifier.pickerGesture(onStart: (Offset, Size) -> Unit, onMove: (Offset, Size) -> Unit,
    onEnd: () -> Unit): Modifier = pointerInput(onStart, onMove, onEnd) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val extent = Size(size.width.toFloat(), size.height.toFloat())
        if (extent.width <= 0f || extent.height <= 0f) return@awaitEachGesture
        down.consume()
        onStart(down.position, extent)
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                onMove(change.position, extent)
                change.consume()
            }
        } finally { onEnd() }
    }
}

/** Absolute saturation/brightness square, not a recentering relative adjustment field. */
@Composable
internal fun ColourPlane(hsv: PickerHsv, onChoose: (PickerHsv) -> Unit, onSettled: () -> Unit) {
    val current by rememberUpdatedState(hsv)
    val choose by rememberUpdatedState(onChoose)
    val settled by rememberUpdatedState(onSettled)
    val pick = remember {
        { p: Offset, s: Size ->
            choose(current.copy(saturation = (p.x / s.width).coerceIn(0f, 1f),
                brightness = (1f - p.y / s.height).coerceIn(0f, 1f)))
        }
    }
    val finish = remember { { settled() } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Canvas(Modifier.size(pickerPlaneSize(maxWidth)).align(Alignment.TopCenter)
            .pickerGesture(pick, pick, finish)
            .semantics { contentDescription = "Saturation and brightness" }.testTag("touchColor.plane")) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hsv.copy(saturation = 1f, brightness = 1f).color())))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            pickerKnob(Offset(hsv.saturation * size.width, (1f - hsv.brightness) * size.height), hsv.color(), 9.dp.toPx())
        }
    }
}

/** Gradient hue track; absolute hue and a moving marker share the same coordinate system. */
@Composable
internal fun HueStrip(hsv: PickerHsv, onChoose: (PickerHsv) -> Unit, onSettled: () -> Unit) {
    val current by rememberUpdatedState(hsv)
    val choose by rememberUpdatedState(onChoose)
    val settled by rememberUpdatedState(onSettled)
    val pick = remember { { p: Offset, s: Size ->
        choose(current.copy(hue = (p.x / s.width).coerceIn(0f, 0.999999f) * 360f))
    } }
    val finish = remember { { settled() } }
    val colors = remember { (0..6).map { PickerHsv((it % 6) * 60f, 1f, 1f).color() } }
    Canvas(Modifier.fillMaxWidth().height(PickerHueHeight)
        .pickerGesture(pick, pick, finish)
        .semantics { contentDescription = "Hue" }.testTag("touchColor.hue")) {
        drawRect(Brush.horizontalGradient(colors), topLeft = Offset(0f, size.height * 0.35f),
            size = Size(size.width, size.height * 0.30f))
        pickerKnob(Offset(hsv.hue / 360f * size.width, center.y), hsv.copy(saturation = 1f, brightness = 1f).color(), 9.dp.toPx())
    }
}

/** A bounded raster, keyed only by the component that changes the picture, not by marker movement. */
private fun pickerPicture(hsv: PickerHsv, harmony: Boolean): Bitmap {
    val n = PICTURE_SIDE
    val pixels = IntArray(n * n)
    for (y in 0 until n) for (x in 0 until n) {
        val u = (x + 0.5f) * 2f / n - 1f
        val v = (y + 0.5f) * 2f / n - 1f
        val distance = hypot(u, v)
        if (distance > 1f) continue
        val color = if (harmony) PickerHsv(pickerHueAt(u, v), distance, hsv.brightness)
            else pickerDiscToSquare(u, v).let { PickerHsv(hsv.hue, (it.x + 1f) / 2f, (1f - it.y) / 2f) }
        pixels[y * n + x] = color.color().toArgb()
    }
    return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
}

/** ProCraft-style ring plus saturation/value disc, or a hue/saturation harmony wheel. */
@Composable
internal fun PickerDisc(hsv: PickerHsv, partners: List<Float>?, onChoose: (PickerHsv) -> Unit,
    onSettled: () -> Unit) {
    val harmony = partners != null
    val current by rememberUpdatedState(hsv)
    val choose by rememberUpdatedState(onChoose)
    val settled by rememberUpdatedState(onSettled)
    val isRing = remember(harmony) { booleanArrayOf(false) }
    val move = remember(harmony) { { p: Offset, s: Size ->
        val half = minOf(s.width, s.height) / 2f
        val x = (p.x - s.width / 2f) / half
        val y = (p.y - s.height / 2f) / half
        val old = current
        when {
            harmony -> choose(old.copy(hue = if (hypot(x, y) > 0.0001f) pickerHueAt(x, y) else old.hue,
                saturation = (hypot(x, y) / 0.90f).coerceIn(0f, 1f)))
            isRing[0] -> if (hypot(x, y) > 0.0001f) choose(old.copy(hue = pickerHueAt(x, y)))
            else -> {
                val point = pickerDiscToSquare(x / DISC_RADIUS, y / DISC_RADIUS)
                choose(old.copy(saturation = (point.x + 1f) / 2f, brightness = (1f - point.y) / 2f))
            }
        }
    } }
    val start = remember(harmony) { { p: Offset, s: Size ->
        val distance = hypot(p.x - s.width / 2f, p.y - s.height / 2f) / (minOf(s.width, s.height) / 2f)
        isRing[0] = !harmony && distance > (DISC_RADIUS + RING_MID - RING_WIDTH / 2f) / 2f
        move(p, s)
    } }
    val finish = remember { { settled() } }
    val component = if (harmony) hsv.brightness else hsv.hue
    val picture = remember(harmony, component) { pickerPicture(hsv, harmony).asImageBitmap() }
    val ring = remember { Brush.sweepGradient((0..6).map { PickerHsv(((6 - it) % 6) * 60f, 1f, 1f).color() }) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Canvas(Modifier.size(pickerPlaneSize(maxWidth)).align(Alignment.TopCenter)
            .pickerGesture(start, move, finish)
            .semantics { contentDescription = if (harmony) "Harmony wheel" else "Hue ring and saturation brightness disc" }
            .testTag(if (harmony) "touchColor.harmony" else "touchColor.disc")) {
            val half = size.minDimension / 2f
            val radius = half * if (harmony) 0.90f else DISC_RADIUS
            val side = (radius * 2f).roundToInt().coerceAtLeast(1)
            if (!harmony) drawCircle(ring, radius = half * RING_MID, style = Stroke(half * RING_WIDTH))
            drawImage(picture, dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
                dstSize = IntSize(side, side))
            fun at(hue: Float, r: Float): Offset {
                val radians = hue * PI.toFloat() / 180f
                return center + Offset(cos(radians) * r, -sin(radians) * r)
            }
            if (harmony) {
                partners.orEmpty().forEach { h ->
                    pickerKnob(at(h, hsv.saturation * radius), hsv.copy(hue = h).color(), 7.dp.toPx())
                }
                pickerKnob(at(hsv.hue, hsv.saturation * radius), hsv.color(), 10.dp.toPx())
            } else {
                pickerKnob(at(hsv.hue, half * RING_MID), hsv.copy(saturation = 1f, brightness = 1f).color(), 11.dp.toPx())
                val point = pickerSquareToDisc(hsv.saturation * 2f - 1f, 1f - hsv.brightness * 2f)
                pickerKnob(center + Offset(point.x * radius, point.y * radius), hsv.color(), 8.dp.toPx())
            }
        }
    }
}

/** Accessible component slider with commit on release. */
@Composable
internal fun PickerSlider(label: String, value: Float, onChange: (Float) -> Unit, onSettled: () -> Unit) {
    Column {
        Text("$label ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = onSettled,
            modifier = Modifier.fillMaxWidth().testTag("touchColor.slider.$label"))
    }
}

private fun DrawScope.pickerKnob(point: Offset, color: Color, radius: Float) {
    drawCircle(Color.Black, radius + 2.dp.toPx(), point)
    drawCircle(Color.White, radius + 1.dp.toPx(), point)
    drawCircle(color, radius - 1.dp.toPx(), point)
}
