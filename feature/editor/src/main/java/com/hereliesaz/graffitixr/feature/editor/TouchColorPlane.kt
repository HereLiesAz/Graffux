@file:Suppress("FunctionNaming")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** How far the plane's edge moves saturation and brightness away from the selected colour (0.5 of the range). */
private const val PLANE_SPAN = 0.5f

/** How far the hue strip's edge moves the hue away from the selected colour. */
private const val HUE_SPAN_DEGREES = 90f
private const val HUE_FULL_TURN = 360f
private const val ALPHA_MAX = 255f

/** The three components of an HSV colour: hue, saturation, value. */
private const val HSV_SIZE = 3

/** A tile or stop's centre is half-way across it. */
private const val HALF = 0.5f

/** The plane is drawn as this many tiles a side, the hue strip as this many stops. */
private const val PLANE_CELLS = 24
private const val HUE_STOPS = 48

/** The largest the saturation/brightness plane gets: on a phone it takes the sheet's width instead. */
internal val PickerPlaneMax: Dp = 280.dp

/** The hue strip's height: a finger-sized band. */
internal val PickerHueHeight: Dp = 52.dp

private val PlaneMarkerRadius = 12.dp
private val PlaneMarkerRing = 3.dp
private val HueMarkerRadius = 14.dp

/** A relative colour field with the exact selected colour at (0, 0). */
internal fun relativePickerColour(base: Color, x: Float, y: Float): Color {
    if (x == 0f && y == 0f) return base
    val hsv = FloatArray(HSV_SIZE).also { android.graphics.Color.colorToHSV(base.toArgb(), it) }
    hsv[1] = (hsv[1] + x.coerceIn(-1f, 1f) * PLANE_SPAN).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] - y.coerceIn(-1f, 1f) * PLANE_SPAN).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor((base.alpha * ALPHA_MAX).roundToInt(), hsv))
}

/** [base] with its hue moved by [offset] (-1..1) of the strip's span; its alpha is kept. */
internal fun hueShiftedColour(base: Color, offset: Float): Color {
    val hsv = FloatArray(HSV_SIZE).also { android.graphics.Color.colorToHSV(base.toArgb(), it) }
    hsv[0] = (hsv[0] + offset.coerceIn(-1f, 1f) * HUE_SPAN_DEGREES + HUE_FULL_TURN) % HUE_FULL_TURN
    return Color(android.graphics.Color.HSVToColor((base.alpha * ALPHA_MAX).roundToInt(), hsv))
}

/**
 * The plane's side for a sheet [available] wide: the whole width, up to [PickerPlaneMax]. Always one number, so
 * the plane stays square on a 152dp-wide phone sheet as on a tablet.
 */
internal fun pickerPlaneSize(available: Dp): Dp = minOf(available, PickerPlaneMax).coerceAtLeast(0.dp)

/**
 * The square saturation/brightness plane: the selected colour at its centre, tap or drag anywhere. [current]
 * is the colour a drag started from; [onChoose] applies a colour live; [onSettled] says a gesture ended.
 */
@Composable
internal fun ColourPlane(
    selected: Color,
    current: () -> Color,
    onChoose: (Color) -> Unit,
    onSettled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentNow by rememberUpdatedState(current)
    val choose by rememberUpdatedState(onChoose)
    val settled by rememberUpdatedState(onSettled)
    val description = stringResource(R.string.colour_plane_description)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val side = pickerPlaneSize(maxWidth)
        Canvas(
            Modifier
                .size(side)
                .align(Alignment.TopCenter)
                .pointerInput(Unit) {
                    detectTapGestures {
                        choose(planeColour(it, size.width.toFloat(), size.height.toFloat(), currentNow()))
                        settled()
                    }
                }
                .pointerInput(Unit) {
                    var start = Color.White
                    detectDragGestures(
                        onDragStart = {
                            start = currentNow()
                            choose(planeColour(it, size.width.toFloat(), size.height.toFloat(), start))
                        },
                        onDragEnd = { settled() },
                        onDragCancel = { settled() },
                        onDrag = { change, _ ->
                            choose(planeColour(change.position, size.width.toFloat(), size.height.toFloat(), start))
                            change.consume()
                        },
                    )
                }
                .semantics { contentDescription = description }
                .testTag("touchColor.plane"),
        ) { drawPlane(selected) }
    }
}

private fun planeColour(p: Offset, w: Float, h: Float, base: Color): Color =
    relativePickerColour(base, 2f * p.x / w - 1f, 2f * p.y / h - 1f)

private fun DrawScope.drawPlane(selected: Color) {
    val tile = Size(size.width / PLANE_CELLS, size.height / PLANE_CELLS)
    for (row in 0 until PLANE_CELLS) for (col in 0 until PLANE_CELLS) {
        val x = (col + HALF) * 2f / PLANE_CELLS - 1f
        val y = (row + HALF) * 2f / PLANE_CELLS - 1f
        drawRect(
            relativePickerColour(selected, x, y),
            topLeft = Offset(col * tile.width, row * tile.height),
            size = tile,
        )
    }
    drawCircle(Color.Black, radius = PlaneMarkerRadius.toPx(), center = center)
    drawCircle(
        Color.White, radius = PlaneMarkerRadius.toPx() - 2.dp.toPx(), center = center,
        style = Stroke(width = PlaneMarkerRing.toPx()),
    )
    drawCircle(selected, radius = PlaneMarkerRadius.toPx() - 5.dp.toPx(), center = center)
}

/** The hue strip: the selected colour's hue at its centre, drag left or right to shift it. */
@Composable
internal fun HueStrip(
    selected: Color,
    current: () -> Color,
    onChoose: (Color) -> Unit,
    onSettled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentNow by rememberUpdatedState(current)
    val choose by rememberUpdatedState(onChoose)
    val settled by rememberUpdatedState(onSettled)
    val description = stringResource(R.string.colour_hue_description)
    Canvas(
        modifier.fillMaxWidth().height(PickerHueHeight)
            .pointerInput(Unit) {
                detectTapGestures {
                    choose(hueShiftedColour(currentNow(), 2f * it.x / size.width - 1f))
                    settled()
                }
            }
            .pointerInput(Unit) {
                var start = Color.White
                detectDragGestures(
                    onDragStart = {
                        start = currentNow()
                        choose(hueShiftedColour(start, 2f * it.x / size.width - 1f))
                    },
                    onDragEnd = { settled() },
                    onDragCancel = { settled() },
                    onDrag = { change, _ ->
                        choose(hueShiftedColour(start, 2f * change.position.x / size.width - 1f))
                        change.consume()
                    },
                )
            }
            .semantics { contentDescription = description }
            .testTag("touchColor.hue"),
    ) {
        for (i in 0 until HUE_STOPS) {
            drawRect(
                hueShiftedColour(selected.copy(alpha = 1f), 2f * (i + HALF) / HUE_STOPS - 1f),
                topLeft = Offset(size.width * i / HUE_STOPS, 0f),
                size = Size(size.width / HUE_STOPS, size.height),
            )
        }
        drawCircle(Color.Black, radius = HueMarkerRadius.toPx(), center = center)
        drawCircle(
            Color.White, radius = HueMarkerRadius.toPx() - 3.dp.toPx(), center = center,
            style = Stroke(width = PlaneMarkerRing.toPx()),
        )
    }
}
