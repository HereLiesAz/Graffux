@file:Suppress("MagicNumber") // Wheel geometry: degrees and dp read better inline.

package com.hereliesaz.graffux

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Which side the set wheel sits on: true (the default, right-handed, rail on the left) puts it on
 * the right. MainActivity provides `uiState.isRightHanded`, so the wheel always sits away from the rail.
 */
internal val LocalSetWheelOnRight = staticCompositionLocalOf { true }

/** Degrees between neighbouring spokes: the quarter's 90° holds the pointed set and two each side. */
internal const val WHEEL_WEDGE_DEGREES = 22.5f

/** The visible arc: a quarter disc in the sheet's corner. */
internal const val WHEEL_ARC_DEGREES = 90f

/** How long the wheel must rest on a wedge before that set is committed (drag, fling, snap, tap). */
internal const val WHEEL_DWELL_MS = 1_000L

/** How far past the first or last set a drag may pull the wheel, in wedges. */
internal const val WHEEL_OVERSCROLL = 0.35f

/** The wheel's largest radius; the peek strip is this tall, less when the space is smaller. */
internal val WheelMaxRadius = 136.dp

/** Names start this far from the hub, so neighbours are clear of one another (22.5° ≈ 20dp there). */
private val WheelTextInner = 52.dp
private val WheelRimPadding = 8.dp
private val WheelHubRadius = 18.dp
private const val FLING_SECONDS = 0.18f
private const val DIM_ALPHA = 0.5f
private const val HALF_TURN = 180f

/**
 * Where the arc starts (screen degrees, 0° = +x, 90° = down): the right-hand wheel's hub is the
 * sheet's bottom-right corner and its quarter runs 180°–270° (left to up); the left-hand one's hub is
 * the bottom-left corner and its quarter runs 270°–360° (up to right).
 */
internal fun wheelArcStart(onRight: Boolean): Float = if (onRight) HALF_TURN else 270f

/** The fixed pointer's spoke: the quarter's midpoint, 45° into the arc. */
internal fun wheelPointerDegrees(onRight: Boolean): Float = wheelArcStart(onRight) + WHEEL_ARC_DEGREES / 2

/**
 * The spoke angle, in degrees, of set [index] while the wheel sits at [position] (a fractional set
 * index under the pointer). Either way round the first sets lie toward the top and the later ones
 * toward the bottom edge, so [onRight] mirrors the order with the corner.
 */
internal fun wheelSpokeDegrees(index: Int, position: Float, onRight: Boolean): Float =
    wheelPointerDegrees(onRight) + (if (onRight) -1f else 1f) * (index - position) * WHEEL_WEDGE_DEGREES

/** The fractional set index at screen angle [degrees], with the wheel at [position]. */
internal fun wheelIndexAt(degrees: Float, position: Float, onRight: Boolean): Float =
    position + (if (onRight) -1f else 1f) * angleDelta(wheelPointerDegrees(onRight), degrees) / WHEEL_WEDGE_DEGREES

/** The signed shortest turn from [from] to [to], in (-180, 180]. */
internal fun angleDelta(from: Float, to: Float): Float {
    var d = (to - from) % 360f
    if (d > HALF_TURN) d -= 360f
    if (d <= -HALF_TURN) d += 360f
    return d
}

/**
 * Where a drag may leave the wheel: inside the sets, with a [WHEEL_OVERSCROLL] pull past either end
 * that gives half as much as the finger moves.
 */
internal fun wheelDragPosition(position: Float, delta: Float, count: Int): Float {
    val last = (count - 1).toFloat()
    val outside = position < 0f || position > last
    val next = position + if (outside) delta / 2 else delta
    return next.coerceIn(-WHEEL_OVERSCROLL, last + WHEEL_OVERSCROLL)
}

/** The set a release at [position] moving at [velocity] (sets per second) settles on. */
internal fun wheelSnapTarget(position: Float, velocity: Float, count: Int): Int =
    (position + velocity * FLING_SECONDS).roundToInt().coerceIn(0, count - 1)

/**
 * The text's rotation on spoke [degrees] so it lies along the spoke and never reads upside down:
 * spokes from 180° to 270° (the right-hand quarter) read in toward the hub, 270° to 360° (the
 * left-hand quarter) read outward.
 */
internal fun wheelTextRotation(degrees: Float): Float {
    val a = ((degrees % 360f) + 360f) % 360f
    return when {
        a in HALF_TURN..<270f -> a - HALF_TURN
        a >= 270f -> a - 360f
        else -> a
    }
}

/**
 * The set picker's wheel: a quarter disc in the sheet's bottom corner away from the rail
 * ([LocalSetWheelOnRight]), its hub in that corner, with every set's name on its own spoke
 * [WHEEL_WEDGE_DEGREES] apart, like a Wheel of Fortune. A fixed pointer at the arc's midpoint (45°)
 * marks the pointed wedge. Drag along the arc to turn it; a release flings and snaps to a wedge, and
 * a tap on a name turns that name to the pointer. The pointed set is only provisional (outlined in
 * the accent) until the wheel has rested on it for [WHEEL_DWELL_MS]; then it is committed through
 * [onPick] (accent fill). Any new motion cancels the wait. Accessibility actions (select a name,
 * next, previous) commit at once. The list is bounded: it stops at the first and last set after a
 * small pull. A light tick plays as each wedge passes the pointer.
 *
 * The wheel's position lives in a float state read only while drawing and placing, so turning it
 * recomposes nothing — not the wheel, and not the cards above it.
 */
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
internal fun CarouselSetWheel(
    sets: List<CarouselTabGroup>,
    labels: Map<String, String>,
    selectedKey: String?,
    radius: Dp,
    onPick: (CarouselTabGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onRight = LocalSetWheelOnRight.current
    val count = sets.size
    val selectedIndex = sets.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val position = remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentPick by rememberUpdatedState(onPick)
    val currentSets by rememberUpdatedState(sets)
    val currentSelected by rememberUpdatedState(selectedIndex)
    val motion = remember { WheelMotion() }

    // Turns to [target]; then, unless [commitNow] already did, commits it after the dwell at rest.
    fun settleTo(target: Int, commitNow: Boolean = false) {
        motion.job?.cancel()
        if (commitNow && target != (motion.committed ?: currentSelected)) {
            currentSets.getOrNull(target)?.let(currentPick)
            motion.committed = target
        }
        motion.job = scope.launch {
            val spec = spring<Float>(Spring.DampingRatioLowBouncy)
            animate(position.floatValue, target.toFloat(), animationSpec = spec) { v, _ ->
                position.floatValue = v
            }
            if (commitNow) return@launch
            delay(WHEEL_DWELL_MS)
            val set = currentSets.getOrNull(target) ?: return@launch
            if (target != currentSelected) currentPick(set)
        }
    }

    // A set picked elsewhere (Favorites, previous) turns the wheel to it.
    LaunchedEffect(selectedIndex) {
        motion.committed = null
        if (!motion.dragging && position.floatValue.roundToInt() != selectedIndex) {
            settleTo(selectedIndex, commitNow = true)
        }
    }
    // A tick each time a wedge crosses the pointer.
    LaunchedEffect(count) {
        var last: Int? = null
        snapshotFlow { position.floatValue.roundToInt().coerceIn(0, count - 1) }.collect { i ->
            if (last != null && last != i) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            last = i
        }
    }

    val density = LocalDensity.current
    val rPx = with(density) { radius.toPx() }
    val innerPx = with(density) { WheelTextInner.toPx() }
    val textMax = (rPx - innerPx - with(density) { WheelRimPadding.toPx() }).coerceAtLeast(1f)
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val baseStyle = MaterialTheme.typography.labelLarge
    val layouts: List<Pair<TextLayoutResult, TextLayoutResult>> = remember(sets, labels, textMax, baseStyle) {
        sets.map { set ->
            val text = labels[set.key] ?: set.label
            fun measure(bold: Boolean) = measurer.measure(
                text, if (bold) baseStyle.copy(fontWeight = FontWeight.Bold) else baseStyle,
                overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = textMax.toInt()),
            )
            measure(false) to measure(true)
        }
    }
    val arcStart = wheelArcStart(onRight)
    val pointer = wheelPointerDegrees(onRight)

    fun move(by: Int) {
        val from = motion.committed ?: currentSelected
        val target = (from + by).coerceIn(0, count - 1)
        if (target != from) settleTo(target, commitNow = true)
    }

    Box(
        modifier
            .size(radius)
            .clipToBounds()
            .testTag("carousel.wheel")
            .semantics {
                selectableGroup()
                contentDescription = "Sets"
                collectionInfo = CollectionInfo(rowCount = count, columnCount = 1)
                customActions = listOf(
                    CustomAccessibilityAction("Next set") { move(1); true },
                    CustomAccessibilityAction("Previous set") { move(-1); true },
                )
            }
            .pointerInput(count, onRight) {
                val hub = Offset(if (onRight) size.width.toFloat() else 0f, size.height.toFloat())
                fun angleOf(p: Offset) = (atan2(p.y - hub.y, p.x - hub.x) * HALF_TURN / PI).toFloat()
                val tracker = VelocityTracker1D(isDataDifferential = false)
                var lastAngle = 0f
                detectDragGestures(
                    onDragStart = { start ->
                        motion.job?.cancel()
                        motion.dragging = true
                        tracker.resetTracking()
                        lastAngle = angleOf(start)
                    },
                    onDragEnd = {
                        motion.dragging = false
                        val v = tracker.calculateVelocity()
                        settleTo(wheelSnapTarget(position.floatValue, if (v.isFinite()) v else 0f, count))
                    },
                    onDragCancel = {
                        motion.dragging = false
                        settleTo(wheelSnapTarget(position.floatValue, 0f, count))
                    },
                ) { change, _ ->
                    val a = angleOf(change.position)
                    val turned = angleDelta(lastAngle, a)
                    lastAngle = a
                    change.consume()
                    // The names follow the finger round the hub.
                    val delta = -(if (onRight) -1f else 1f) * turned / WHEEL_WEDGE_DEGREES
                    position.floatValue = wheelDragPosition(position.floatValue, delta, count)
                    tracker.addDataPoint(change.uptimeMillis, position.floatValue)
                }
            }
            .pointerInput(count, onRight) {
                detectTapGestures { tap ->
                    val hub = Offset(if (onRight) size.width.toFloat() else 0f, size.height.toFloat())
                    if (hypot(tap.x - hub.x, tap.y - hub.y) > size.height) return@detectTapGestures
                    val a = (atan2(tap.y - hub.y, tap.x - hub.x) * HALF_TURN / PI).toFloat()
                    settleTo(wheelIndexAt(a, position.floatValue, onRight).roundToInt().coerceIn(0, count - 1))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val hub = Offset(if (onRight) size.width else 0f, size.height)
            val r = size.height
            val pos = position.floatValue
            val pointed = pos.roundToInt().coerceIn(0, count - 1)
            val committed = pointed == selectedIndex
            // The disc, the pointed wedge (filled once committed, outlined while provisional), the
            // rim and the pointer.
            val box = Size(r * 2, r * 2)
            val corner = hub - Offset(r, r)
            val wedgeStart = pointer - WHEEL_WEDGE_DEGREES / 2
            drawArc(colors.surface.copy(alpha = 0.72f), arcStart, WHEEL_ARC_DEGREES, true, corner, box)
            if (committed) {
                drawArc(colors.primary.copy(alpha = 0.28f), wedgeStart, WHEEL_WEDGE_DEGREES, true, corner, box)
            } else {
                drawArc(
                    colors.primary, wedgeStart, WHEEL_WEDGE_DEGREES, true, corner, box,
                    style = Stroke(1.5.dp.toPx()),
                )
            }
            drawArc(
                colors.outline, arcStart, WHEEL_ARC_DEGREES, false, corner + Offset(1f, 1f),
                Size(r * 2 - 2f, r * 2 - 2f), style = Stroke(1.dp.toPx()),
            )
            val tip = 8.dp.toPx()
            rotate(pointer - 270f, pivot = hub) {
                // Drawn pointing down onto the rim at the top, then turned onto the pointer spoke.
                drawPath(
                    Path().apply {
                        moveTo(hub.x - tip, hub.y - r)
                        lineTo(hub.x + tip, hub.y - r)
                        lineTo(hub.x, hub.y - r + tip * 1.4f)
                        close()
                    },
                    colors.primary,
                )
            }
            drawCircle(colors.outline, WheelHubRadius.toPx(), hub, style = Stroke(1.dp.toPx()))
            layouts.forEachIndexed { i, (plain, bold) ->
                val spoke = wheelSpokeDegrees(i, pos, onRight)
                if (angleDelta(pointer, spoke) !in -WHEEL_ARC_DEGREES..WHEEL_ARC_DEGREES) return@forEachIndexed
                val lit = (i - pos) in -0.5f..0.5f
                val layout = if (lit) bold else plain
                val rot = wheelTextRotation(spoke)
                // Inward-reading names end near the hub; outward-reading ones start there.
                val inward = rot >= 0f
                rotate(rot, pivot = hub) {
                    val x = if (inward) hub.x - innerPx - layout.size.width else hub.x + innerPx
                    drawText(
                        layout,
                        color = if (lit) colors.onSurface else colors.onSurface.copy(alpha = DIM_ALPHA),
                        topLeft = Offset(x, hub.y - layout.size.height / 2f),
                    )
                }
            }
        }
        // One selectable node per set, riding on its name, for accessibility services: selecting
        // one commits it at once. Placement only, so turning the wheel recomposes nothing. Touches
        // pass through to the wheel's own tap handling.
        val itemSize = 28.dp
        val itemPx = with(density) { itemSize.toPx() }
        val hubX = if (onRight) rPx else 0f
        sets.forEachIndexed { i, set ->
            val label = labels[set.key] ?: set.label
            Box(
                Modifier
                    .offset {
                        val spoke = wheelSpokeDegrees(i, position.floatValue, onRight) * (PI / HALF_TURN).toFloat()
                        val mid = innerPx + layouts[i].first.size.width / 2f
                        IntOffset(
                            (hubX + mid * cos(spoke) - itemPx / 2).roundToInt(),
                            (rPx + mid * sin(spoke) - itemPx / 2).roundToInt(),
                        )
                    }
                    .size(itemSize)
                    .semantics {
                        text = AnnotatedString(label)
                        selected = i == selectedIndex
                        role = Role.Tab
                        collectionItemInfo = CollectionItemInfo(i, 1, 0, 1)
                        onClick(label = "Select") { settleTo(i, commitNow = true); true }
                    }
                    .testTag("carousel.wheel.item"),
            )
        }
    }
}

/** The wheel's running motion: the turn (and dwell) in progress, and whether a finger is on it. */
private class WheelMotion {
    var job: Job? = null
    var dragging = false

    /** A set committed at once (accessibility) that the caller has not yet handed back as selected. */
    var committed: Int? = null
}

internal val QuickColumnWidth = 112.dp
internal val PickerGap = 8.dp

/** Gaps across the picker: both sides and between the buttons and the wheel. */
internal const val PICKER_GAPS = 3

/**
 * The wheel's radius in a picker [width] wide and at most [height] tall (unbounded while the sheet
 * wraps its content): [WheelMaxRadius], less when the quarter disc (as wide as it is tall) and the
 * quick-button column would not fit side by side. The peek strip is exactly this tall.
 */
internal fun setWheelRadius(width: Dp, height: Dp) =
    min(min(WheelMaxRadius, height), width - QuickColumnWidth - PickerGap * PICKER_GAPS)
