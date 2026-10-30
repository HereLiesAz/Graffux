@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")

package com.hereliesaz.graffux

import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.photo.CurveChannel
import com.hereliesaz.graffitixr.common.photo.CurveLut
import com.hereliesaz.graffitixr.common.photo.CurvePoint
import com.hereliesaz.graffitixr.common.photo.HueRange
import com.hereliesaz.graffitixr.common.photo.PhotoAdjustment
import com.hereliesaz.graffitixr.common.photo.PhotoSliders
import com.hereliesaz.graffitixr.common.photo.PhotoTool
import com.hereliesaz.graffitixr.design.theme.RobotoCondensedFamily
import com.hereliesaz.graffitixr.design.theme.RobotoMonoFamily
import com.hereliesaz.graffitixr.feature.editor.CurvesAdjustment
import com.hereliesaz.graffitixr.feature.editor.CurvesStyle
import kotlin.math.ln
import kotlin.math.pow

/*
 * The bodies of the photo tabs that are more than a list of sliders: HSL (eight swatches, then
 * Hue, Saturation and Luminance for the picked range) and Curves (channel, the curve over a
 * histogram, Levels underneath). PhotoPanel picks the body by tab.
 */

/**
 * HSL: the eight ranges as plain swatches (the picked one ringed), then its three sliders. The
 * header's eyedropper picks a range from the photo instead (MainActivity's overlay).
 */
@Composable
internal fun HslBody(adjustment: PhotoAdjustment?, actions: PhotoActions, drag: PhotoDrag, state: PhotoSheetState) {
    val range = state.hslRange
    PhotoSliderList(
        tool = PhotoTool.HSL,
        sliders = (0..2).map { PhotoSliders.hsl(range, it) },
        adjustment = adjustment,
        actions = actions,
        drag = drag,
        header = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 19.dp)
                    .alpha(if (drag.active) 0f else 1f),
            ) {
                HueRange.entries.forEach { r -> HueSwatch(r, r == range) { state.hslRange = r } }
            }
        },
    )
}

@Composable
private fun HueSwatch(range: HueRange, picked: Boolean, onPick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .selectable(selected = picked, role = Role.RadioButton, onClick = onPick)
            .semantics { contentDescription = range.label }
            .testTag("photo.hsl.${range.name.lowercase()}"),
    ) {
        if (picked) {
            // CSS outline 2px at offset 3: a ring 36 across around the 26 swatch.
            Box(Modifier.size(36.dp).border(2.dp, PhotoSheetStyle.Ink, CircleShape))
        }
        Box(Modifier.size(26.dp).background(Color(range.swatchArgb), CircleShape))
    }
}

/** Curves: the channel, the curve over the photo's histogram, and Levels in the row under it. */
@Composable
internal fun CurvesBody(adjustment: PhotoAdjustment?, actions: PhotoActions, drag: PhotoDrag, state: PhotoSheetState) {
    val channel = state.curveChannel
    val points = (adjustment?.curves?.get(channel) ?: PhotoAdjustment.IDENTITY_CURVE)
    Column(Modifier.fillMaxWidth().alpha(if (drag.active) 0f else 1f)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp),
        ) {
            CurveChannel.entries.forEach { c -> ChannelChip(c, c == channel) { state.curveChannel = c } }
        }
        Box(Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 20.dp).testTag("photo.curve")) {
            CurvesAdjustment(
                points = points.map { Offset(it.x, 1f - it.y) },
                onPointsChanged = { pts ->
                    val model = pts.map { CurvePoint(it.x, 1f - it.y) }
                    actions.update(PhotoTool.CURVES) { it.copy(curves = it.curves + (channel to model)) }
                },
                onDragStart = { actions.gestureStart(PhotoTool.CURVES) },
                onDragEnd = { actions.gestureEnd() },
                editablePoints = true,
                curve = CurveLut.build(points),
                histogram = state.histogram,
                style = CurvesStyle.Photo,
            )
        }
        LevelsRow(adjustment, actions)
    }
}

@Composable
private fun ChannelChip(channel: CurveChannel, picked: Boolean, onPick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (picked) PhotoSheetStyle.Ink else Color.Transparent)
            .then(if (picked) Modifier else Modifier.border(1.dp, RailBorder, RoundedCornerShape(18.dp)))
            .selectable(selected = picked, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 14.dp)
            .testTag("photo.channel.${channel.name.lowercase()}"),
    ) {
        Text(
            channel.label,
            color = if (picked) Color(0xFF0E0E0F) else PhotoSheetStyle.Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = RobotoCondensedFamily,
        )
    }
}

/** Where Levels' midpoint handle sits between black and white (0..1) for a gamma, and back. */
internal fun levelsMidFraction(gamma: Float): Float = 0.5f.pow(1f / gamma)

internal fun levelsGammaAt(fraction: Float): Float =
    (ln(fraction.coerceIn(0.01f, 0.99f)) / ln(0.5f)).let { 1f / it }.coerceIn(PhotoSliders.LEVELS_MID.min, PhotoSliders.LEVELS_MID.max)

/**
 * A Levels handle's new value for a set-progress request (TalkBack / switch access), kept in order:
 * black stays below white and white above black. Returns the slider to write and its value.
 */
internal fun levelsTargetValue(which: String, target: Float, black: Float, white: Float): Pair<com.hereliesaz.graffitixr.common.photo.PhotoSlider, Float> =
    when (which) {
        "black" -> PhotoSliders.LEVELS_BLACK to target.coerceIn(0f, white - 1f)
        "white" -> PhotoSliders.LEVELS_WHITE to target.coerceIn(black + 1f, 255f)
        else -> PhotoSliders.LEVELS_MID to PhotoSliders.LEVELS_MID.snap(target.coerceIn(PhotoSliders.LEVELS_MID.min, PhotoSliders.LEVELS_MID.max))
    }

/**
 * Levels, folded into Curves: black point, midpoint and white point on a black-to-white track,
 * their values to the right ("12 · 1.10 · 241"). Each handle drags; the midpoint stays between
 * the other two.
 */
@Composable
private fun LevelsRow(adjustment: PhotoAdjustment?, actions: PhotoActions) {
    val black = adjustment?.value(PhotoSliders.LEVELS_BLACK) ?: PhotoSliders.LEVELS_BLACK.default
    val white = adjustment?.value(PhotoSliders.LEVELS_WHITE) ?: PhotoSliders.LEVELS_WHITE.default
    val gamma = adjustment?.value(PhotoSliders.LEVELS_MID) ?: PhotoSliders.LEVELS_MID.default
    val latest by rememberUpdatedState(Triple(black, gamma, white))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        // heightIn, not height: the text may grow with the system font scale.
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp).testTag("photo.levels"),
    ) {
        Text("Levels", color = PhotoSheetStyle.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = RobotoCondensedFamily, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 44.dp))
        BoxWithConstraints(Modifier.weight(1f).height(48.dp)) {
            // 48dp touch targets; the drawn triangle is still 14dp wide, centred in each.
            val handle = 48.dp
            val span = maxWidth - handle
            var spanPx by remember { mutableFloatStateOf(1f) }
            Box(
                Modifier
                    .padding(top = 21.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Black, PhotoSheetStyle.Ink))),
            )
            val midT = black / 255f + (white - black) / 255f * levelsMidFraction(gamma)
            listOf(
                Triple("black", black / 255f, Color(0xFF0E0E0F)),
                Triple("mid", midT, Color(0xFF8A8A90)),
                Triple("white", white / 255f, PhotoSheetStyle.Ink),
            ).forEach { (which, t, fill) ->
                Box(
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier
                        .offset(x = span * t.coerceIn(0f, 1f))
                        .width(handle)
                        .height(48.dp)
                        // Operable without the drag: a slider TalkBack can step or set.
                        .semantics {
                            contentDescription = "Levels ${if (which == "mid") "midpoint" else "$which point"}"
                            val (current, range) = when (which) {
                                "black" -> black to 0f..255f
                                "white" -> white to 0f..255f
                                else -> gamma to PhotoSliders.LEVELS_MID.min..PhotoSliders.LEVELS_MID.max
                            }
                            progressBarRangeInfo = ProgressBarRangeInfo(current, range)
                            setProgress { target ->
                                val (slider, value) = levelsTargetValue(which, target, latest.first, latest.third)
                                actions.gestureStart(PhotoTool.CURVES)
                                actions.slider(PhotoTool.CURVES, slider, value)
                                actions.gestureEnd()
                                true
                            }
                        }
                        .pointerInput(which) {
                            spanPx = (maxWidth - handle).toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                actions.gestureStart(PhotoTool.CURVES)
                                var x = when (which) {
                                    "black" -> latest.first / 255f
                                    "white" -> latest.third / 255f
                                    else -> latest.first / 255f + (latest.third - latest.first) / 255f * levelsMidFraction(latest.second)
                                } * spanPx
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    c.consume()
                                    x += c.positionChangeIgnoreConsumed().x
                                    val v = (x / spanPx).coerceIn(0f, 1f)
                                    val (b, _, w) = latest
                                    when (which) {
                                        "black" -> actions.slider(PhotoTool.CURVES, PhotoSliders.LEVELS_BLACK, (v * 255f).coerceAtMost(w - 1f))
                                        "white" -> actions.slider(PhotoTool.CURVES, PhotoSliders.LEVELS_WHITE, (v * 255f).coerceAtLeast(b + 1f))
                                        else -> {
                                            val f = ((v * 255f - b) / (w - b).coerceAtLeast(1f)).coerceIn(0.01f, 0.99f)
                                            actions.slider(PhotoTool.CURVES, PhotoSliders.LEVELS_MID, PhotoSliders.LEVELS_MID.snap(levelsGammaAt(f)))
                                        }
                                    }
                                }
                                actions.gestureEnd()
                            }
                        }
                        .testTag("photo.levels.$which"),
                ) {
                    Canvas(Modifier.padding(top = 22.dp).size(14.dp, 12.dp)) {
                        val tri = Path().apply {
                            moveTo(size.width / 2f, 0f)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(tri, fill)
                        if (which != "white") drawPath(tri, PhotoSheetStyle.Ink, style = Stroke(1.dp.toPx()))
                    }
                }
            }
        }
        Text(
            "${black.toInt()} · ${PhotoSliders.LEVELS_MID.display(gamma)} · ${white.toInt()}",
            color = PhotoSheetStyle.Muted,
            fontSize = 12.sp,
            fontFamily = RobotoMonoFamily,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            // One line, always: it wrapped inside the row at 92dp.
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 92.dp).testTag("photo.levels.value"),
        )
    }
}
