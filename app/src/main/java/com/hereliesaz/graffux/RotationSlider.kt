package com.hereliesaz.graffux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HALF_TURN = 180f
private const val FULL_TURN = 360f
private const val QUARTER_TURN = 90f
private const val EIGHTH_TURN = 45f

/** Haptic detents (degrees). Only 0 also snaps. */
internal val RotationDetents = floatArrayOf(
    -HALF_TURN, -QUARTER_TURN, -EIGHTH_TURN, 0f, EIGHTH_TURN, QUARTER_TURN, HALF_TURN,
)

/** Within this many degrees of 0 a drag sits at 0. */
internal const val ROTATION_SNAP_DEG = 2.5f

/** [raw] with the soft snap at 0 applied and clamped to -180..180. */
internal fun snapRotation(raw: Float): Float {
    val v = raw.coerceIn(-HALF_TURN, HALF_TURN)
    return if (abs(v) < ROTATION_SNAP_DEG) 0f else v
}

/** Whether a move from [from] to [to] crosses (or lands on) a detent. */
internal fun crossesRotationDetent(from: Float, to: Float): Boolean {
    if (from == to) return false
    val lo = minOf(from, to)
    val hi = maxOf(from, to)
    return RotationDetents.any { d -> d != from && d in lo..hi }
}

/**
 * The hairline rotation slider above the bottom sheet: turns the active layer (never the view, which
 * two fingers own) on [value]'s axis, -180..180°. A drag is one gesture ([onStart], live [onChange],
 * [onEnd]); a double-tap on the thumb calls [onReset]. The degree readout shows only while dragging.
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun RotationSlider(
    value: Float,
    onStart: () -> Unit,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    onReset: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var rawValue by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragValue else value
    fun moveTo(raw: Float) {
        rawValue = raw.coerceIn(-HALF_TURN, HALF_TURN)
        val next = snapRotation(rawValue)
        if (crossesRotationDetent(dragValue, next)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        if (next != dragValue) {
            dragValue = next
            onChange(next)
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp)
            .height(RowHeight)
            .testTag("rotationSlider")
            .semantics {
                contentDescription = label
                stateDescription = "${shown.roundToInt()}°"
                progressBarRangeInfo = ProgressBarRangeInfo(shown, -HALF_TURN..HALF_TURN)
                setProgress { target ->
                    onStart()
                    dragValue = current
                    moveTo(target)
                    onEnd()
                    true
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragValue = current
                        rawValue = current
                        onStart()
                    },
                    onDragEnd = { dragging = false; onEnd() },
                    onDragCancel = { dragging = false; onEnd() },
                ) { change, dx ->
                    change.consume()
                    moveTo(rawValue + dx / size.width.coerceAtLeast(1) * FULL_TURN)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        val thumbX = (current + HALF_TURN) / FULL_TURN * size.width
                        if (abs(at.x - thumbX) <= THUMB_HIT_DP * density) onReset()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (dragging) {
            Text(
                "${shown.roundToInt()}°",
                color = colors.onSurface,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopCenter).testTag("rotationSlider.readout"),
            )
        }
        Canvas(Modifier.fillMaxWidth().height(ThumbDiameter).align(Alignment.BottomCenter)) {
            val y = size.height / 2f
            val track = 2.dp.toPx()
            val cx = size.width / 2f
            val tx = (shown + HALF_TURN) / FULL_TURN * size.width
            drawLine(colors.onSurface.copy(alpha = TRACK_ALPHA), Offset(0f, y), Offset(size.width, y), track)
            drawLine(colors.primary, Offset(cx, y), Offset(tx, y), track)
            val tick = TickHalfHeight.toPx()
            drawLine(colors.onSurface.copy(alpha = TICK_ALPHA), Offset(cx, y - tick), Offset(cx, y + tick), 1.dp.toPx())
            drawCircle(colors.primary, radius = size.height / 2f, center = Offset(tx, y))
        }
    }
}

private val RowHeight = 28.dp
private val ThumbDiameter = 10.dp
private const val THUMB_HIT_DP = 24f
private val TickHalfHeight = 4.dp
private const val TRACK_ALPHA = 0.35f
private const val TICK_ALPHA = 0.6f
