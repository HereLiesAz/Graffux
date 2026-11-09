@file:Suppress("MagicNumber", "TooManyFunctions") // Wheel geometry: degrees and dp read better inline.

package com.hereliesaz.graffux

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.asin
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Which side the set wheel sits on: true (the default, right-handed, rail on the left) puts it on
 * the right. MainActivity provides `uiState.isRightHanded`, so the wheel always sits away from the rail.
 */
internal val LocalSetWheelOnRight = staticCompositionLocalOf { true }

/**
 * Where the set wheel is on screen (root px), or null when it is not shown. The wheel writes it; the rotation
 * triangle's dock reads it to keep its touch bands off the wheel (the wheel has the touch priority).
 */
internal val LocalSetWheelBounds =
    staticCompositionLocalOf<androidx.compose.runtime.MutableState<androidx.compose.ui.geometry.Rect?>?> { null }

/**
 * The wheel's outer radius. Its hub sits far below the screen, so the visible part is a wide, shallow
 * band along the sheet's bottom corner rather than a quarter disc.
 */
internal val WheelArcRadius = 420.dp

/** The band's thickness, rim to rim: room for one line of name with air above and below. */
internal val WheelBandThickness = 36.dp

/**
 * The band a touch turns or picks on: the drawn band plus this much more on its inner side (toward the
 * screen's bottom edge, away from the cards), so the target is the 48dp minimum though the band is drawn
 * [WheelBandThickness] thick. The extra is clipped by the box where the band curves out of it.
 */
internal val WheelTouchThickness = 48.dp

/** The outer rim's top (at the pointer) sits this far below the box's top. */
internal val WheelTopPad = 2.dp

/** The radius the names ride on: the band's middle. */
internal val WheelTextRadius = WheelArcRadius - WheelBandThickness / 2

/**
 * Degrees between neighbouring spokes: at [WheelTextRadius] one wedge is about 105dp of arc, wide
 * enough for a set name such as "My Brushes" in full (bold) with [WheelNameGap] to spare.
 */
internal const val WHEEL_WEDGE_DEGREES = 15f

/** Air between neighbouring names along the band. */
internal val WheelNameGap = 8.dp

/** A name's widest drawn width: one wedge of arc less the gap; only a longer name gets an ellipsis. */
internal val WheelNameMaxWidth = WheelTextRadius * (WHEEL_WEDGE_DEGREES * PI.toFloat() / 180f) - WheelNameGap

/** The band is never narrower than this, so typical names fit, nor wider than [WheelMaxWidth]. */
internal val WheelMinWidth = 200.dp
internal val WheelMaxWidth = 272.dp

/** How long the wheel must rest on a wedge before that set is committed (drag, fling, snap, tap). */
internal const val WHEEL_DWELL_MS = 1_000L

/** How far past the first or last set a drag may pull the wheel, in wedges. */
internal const val WHEEL_OVERSCROLL = 0.35f

/**
 * The band's lowest point (its inner rim at the box's outer edges) stays at least this far above the
 * box's bottom, clear of the screen's rounded corner and the nav bar's edge.
 */
internal val WheelEdgeInset = 12.dp

/** The wheel's hub, far below a box [width] wide: centred, [outerRadius] below the rim's top. */
internal object WheelHub {
    fun at(width: Float, outerRadius: Float, topPad: Float): Offset = Offset(width / 2, topPad + outerRadius)
}

/** How far below the box's top the band's inner rim reaches at the edges of a box [width] wide. */
internal fun wheelBandDepth(width: Dp): Dp {
    val inner = (WheelArcRadius - WheelBandThickness).value
    val half = width.value / 2
    return WheelTopPad + WheelBandThickness + (inner - sqrt(inner * inner - half * half)).dp
}

/** The visible arc of the outer rim, in degrees, across a box [width] wide. */
internal fun wheelVisibleArcDegrees(width: Dp): Float =
    2 * Math.toDegrees(asin((width.value / 2 / WheelArcRadius.value).toDouble())).toFloat()

internal const val FLING_SECONDS = 0.18f
private const val DIM_ALPHA = 0.5f
private const val HALF_TURN = 180f
private const val UP = 270f

/** The fixed pointer's spoke: straight up from the hub, the band's midpoint, either hand. */
@Suppress("UnusedParameter") // Kept symmetric with the other wheel functions; the flat arc is centred.
internal fun wheelPointerDegrees(onRight: Boolean): Float = UP

/** +1 when later sets lie to the right (right-handed: away from the rail on the left), else -1. */
private fun wheelDirection(onRight: Boolean) = if (onRight) 1f else -1f

/**
 * The spoke angle, in degrees, of set [index] while the wheel sits at [position] (a fractional set
 * index under the pointer). The first sets lie toward the rail and the later
 * ones away from it, so [onRight] mirrors the order.
 */
internal fun wheelSpokeDegrees(index: Int, position: Float, onRight: Boolean): Float =
    wheelPointerDegrees(onRight) + wheelDirection(onRight) * (index - position) * WHEEL_WEDGE_DEGREES

/** The fractional set index at screen angle [degrees], with the wheel at [position]. */
internal fun wheelIndexAt(degrees: Float, position: Float, onRight: Boolean): Float =
    position + wheelDirection(onRight) * angleDelta(wheelPointerDegrees(onRight), degrees) / WHEEL_WEDGE_DEGREES

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
 * The text's rotation on spoke [degrees]: each name lies along the band, upright at the pointer and
 * leaning with the arc either side, so none ever reads upside down.
 */
internal fun wheelTextRotation(degrees: Float): Float = angleDelta(UP, degrees)

/** Whether a wheel item's name had to be cut with an ellipsis to fit its wedge. */
internal val WheelNameEllipsized = SemanticsPropertyKey<Boolean>("WheelNameEllipsized")
private var SemanticsPropertyReceiver.wheelNameEllipsized by WheelNameEllipsized

/** The ring sector between radii [inner] and [outer] about [hub], from [start] through [sweep] degrees. */
private fun bandSector(hub: Offset, inner: Float, outer: Float, start: Float, sweep: Float) = Path().apply {
    arcTo(Rect(hub, outer), start, sweep, forceMoveTo = true)
    arcTo(Rect(hub, inner), start + sweep, -sweep, forceMoveTo = false)
    close()
}

/**
 * The set picker's wheel: a wide, shallow band along the sheet's bottom corner away from the rail
 * ([LocalSetWheelOnRight]) — the top of a big wheel whose hub is far below the screen — with every
 * set's name on its own spoke [WHEEL_WEDGE_DEGREES] apart, like a Wheel of Fortune. A fixed pointer
 * at the band's midpoint marks the pointed wedge. Drag along the band to turn it; a release flings
 * and snaps to a wedge, and a tap on a name turns that name to the pointer and commits it at once.
 * After a drag or fling the pointed set is only provisional (outlined in the accent) until the wheel
 * has rested on it for [WHEEL_DWELL_MS]; then it is committed through [onPick] (accent fill). Any
 * new motion cancels the wait. Accessibility actions (select a name, next, previous) commit at once.
 * The list is bounded: it stops at the first and last set after a small pull. A light tick plays as
 * each wedge passes the pointer. Only touches on the band itself turn or pick; the rest of its box
 * never takes a swipe.
 *
 * The wheel's position lives in a float state read only while drawing and placing, so turning it
 * recomposes nothing — not the wheel, and not the cards above it.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun CarouselSetWheel(
    sets: List<CarouselTabGroup>,
    labels: Map<String, String>,
    selectedKey: String?,
    width: Dp,
    height: Dp,
    onPick: (CarouselTabGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The hub, the arc and the hit area all follow the width the wheel is actually given, which a
    // crowded row may make less than [width].
    BoxWithConstraints(modifier.size(width, height)) {
        SetWheelBand(sets, labels, selectedKey, maxWidth, maxHeight, onPick)
    }
}

/** The wheel's width its geometry (hub, arc, hit area) was built for, in px. */
internal val WheelGeometryWidth = SemanticsPropertyKey<Float>("WheelGeometryWidth")
private var SemanticsPropertyReceiver.wheelGeometryWidth by WheelGeometryWidth

@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
private fun SetWheelBand(
    sets: List<CarouselTabGroup>,
    labels: Map<String, String>,
    selectedKey: String?,
    width: Dp,
    height: Dp,
    onPick: (CarouselTabGroup) -> Unit,
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

    // A set picked elsewhere turns the wheel to it.
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
    val outerPx = with(density) { WheelArcRadius.toPx() }
    val innerPx = with(density) { (WheelArcRadius - WheelBandThickness).toPx() }
    val touchInnerPx = with(density) { (WheelArcRadius - WheelTouchThickness).toPx() }
    val textRPx = with(density) { WheelTextRadius.toPx() }
    val topPx = with(density) { WheelTopPad.toPx() }
    val widthPx = with(density) { width.toPx() }
    val hub = WheelHub.at(widthPx, outerPx, topPx)
    val nameMax = with(density) { WheelNameMaxWidth.toPx() }
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val baseStyle = MaterialTheme.typography.labelLarge
    val layouts: List<Pair<TextLayoutResult, TextLayoutResult>> = remember(sets, labels, nameMax, baseStyle) {
        sets.map { set ->
            val text = labels[set.key] ?: set.label
            fun measure(bold: Boolean) = measurer.measure(
                text, if (bold) baseStyle.copy(fontWeight = FontWeight.Bold) else baseStyle,
                overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = nameMax.toInt()),
            )
            measure(false) to measure(true)
        }
    }
    val pointer = wheelPointerDegrees(onRight)
    // Enough arc either side of the pointer to reach past the box's edges; the box clips the rest.
    val halfArc = wheelVisibleArcDegrees(width) / 2 + WHEEL_WEDGE_DEGREES
    fun angleOf(p: Offset) = (atan2(p.y - hub.y, p.x - hub.x) * HALF_TURN / PI).toFloat()
    fun onBand(p: Offset) = hypot(p.x - hub.x, p.y - hub.y) in touchInnerPx..outerPx

    fun move(by: Int) {
        val from = motion.committed ?: currentSelected
        val target = (from + by).coerceIn(0, count - 1)
        if (target != from) settleTo(target, commitNow = true)
    }

    Box(
        Modifier
            .size(width, height)
            .clipToBounds()
            .testTag("carousel.wheel")
            .semantics {
                selectableGroup()
                contentDescription = "Sets"
                wheelGeometryWidth = widthPx
                collectionInfo = CollectionInfo(rowCount = count, columnCount = 1)
                customActions = listOf(
                    CustomAccessibilityAction("Next set") { move(1); true },
                    CustomAccessibilityAction("Previous set") { move(-1); true },
                )
            }
            .pointerInput(count, onRight, hub) {
                val tracker = VelocityTracker1D(isDataDifferential = false)
                // Only a touch that lands on the band turns it: the rest of its box (toward the cards)
                // never takes a swipe.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!onBand(down.position)) return@awaitEachGesture
                    var lastAngle = angleOf(down.position)
                    val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    motion.job?.cancel()
                    motion.dragging = true
                    tracker.resetTracking()
                    fun turn(change: androidx.compose.ui.input.pointer.PointerInputChange) {
                        val a = angleOf(change.position)
                        val turned = angleDelta(lastAngle, a)
                        lastAngle = a
                        change.consume()
                        // The names follow the finger along the band.
                        val delta = -wheelDirection(onRight) * turned / WHEEL_WEDGE_DEGREES
                        position.floatValue = wheelDragPosition(position.floatValue, delta, count)
                        tracker.addDataPoint(change.uptimeMillis, position.floatValue)
                    }
                    turn(slop)
                    val completed = drag(slop.id) { turn(it) }
                    motion.dragging = false
                    val v = if (completed) tracker.calculateVelocity() else 0f
                    settleTo(wheelSnapTarget(position.floatValue, if (v.isFinite()) v else 0f, count))
                }
            }
            .pointerInput(count, onRight, hub) {
                detectTapGestures { tap ->
                    if (!onBand(tap)) return@detectTapGestures
                    // A tap is a decision: it commits at once. The dwell is for a drag or fling at rest.
                    settleTo(
                        wheelIndexAt(angleOf(tap), position.floatValue, onRight).roundToInt().coerceIn(0, count - 1),
                        commitNow = true,
                    )
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pos = position.floatValue
            val pointed = pos.roundToInt().coerceIn(0, count - 1)
            val committed = pointed == selectedIndex
            // The band, the pointed wedge (filled once committed, outlined while provisional), the
            // rims and the pointer.
            val band = bandSector(hub, innerPx, outerPx, pointer - halfArc, halfArc * 2)
            drawPath(band, colors.surface.copy(alpha = 0.72f))
            val wedge = bandSector(hub, innerPx, outerPx, pointer - WHEEL_WEDGE_DEGREES / 2, WHEEL_WEDGE_DEGREES)
            if (committed) {
                drawPath(wedge, colors.primary.copy(alpha = 0.28f))
            } else {
                drawPath(wedge, colors.primary, style = Stroke(1.5.dp.toPx()))
            }
            val rim = 1.dp.toPx()
            listOf(outerPx - rim / 2, innerPx + rim / 2).forEach { r ->
                drawArc(
                    colors.outline, pointer - halfArc, halfArc * 2, false, hub - Offset(r, r), Size(r * 2, r * 2),
                    style = Stroke(rim),
                )
            }
            val tip = 7.dp.toPx()
            val top = hub.y - outerPx
            drawPath(
                Path().apply {
                    moveTo(hub.x - tip, top)
                    lineTo(hub.x + tip, top)
                    lineTo(hub.x, top + tip * 1.2f)
                    close()
                },
                colors.primary,
            )
            layouts.forEachIndexed { i, (plain, bold) ->
                val spoke = wheelSpokeDegrees(i, pos, onRight)
                if (angleDelta(pointer, spoke) !in -halfArc..halfArc) return@forEachIndexed
                val lit = (i - pos) in -0.5f..0.5f
                val layout = if (lit) bold else plain
                // Upright at the pointer, leaning with the band either side.
                rotate(wheelTextRotation(spoke), pivot = hub) {
                    drawText(
                        layout,
                        color = if (lit) colors.onSurface else colors.onSurface.copy(alpha = DIM_ALPHA),
                        topLeft = Offset(hub.x - layout.size.width / 2f, hub.y - textRPx - layout.size.height / 2f),
                    )
                }
            }
        }
        // One selectable node per set, riding on its name, for accessibility services: selecting
        // one commits it at once. Placement only, so turning the wheel recomposes nothing. Touches
        // pass through to the wheel's own tap handling.
        val itemSize = WheelTouchThickness
        val itemPx = with(density) { itemSize.toPx() }
        sets.forEachIndexed { i, set ->
            val label = labels[set.key] ?: set.label
            val cut = layouts[i].second.isLineEllipsized(0)
            Box(
                Modifier
                    .offset {
                        val spoke = wheelSpokeDegrees(i, position.floatValue, onRight) * (PI / HALF_TURN).toFloat()
                        IntOffset(
                            (hub.x + textRPx * cos(spoke) - itemPx / 2).roundToInt(),
                            (hub.y + textRPx * sin(spoke) - itemPx / 2).roundToInt(),
                        )
                    }
                    .size(itemSize)
                    .semantics {
                        text = AnnotatedString(label)
                        selected = i == selectedIndex
                        role = Role.Tab
                        collectionItemInfo = CollectionItemInfo(i, 1, 0, 1)
                        wheelNameEllipsized = cut
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

/** The picker strip's side padding. */
internal val PickerGap = 8.dp
