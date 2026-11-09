@file:Suppress("MagicNumber", "TooManyFunctions") // Wheel geometry: degrees and dp read better inline.

package com.hereliesaz.graffux

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.Tool
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The pack wheel's largest radius: room for a full pack name ("My Brushes", "Spray Caps") on a spoke. */
internal val PackWheelMaxRadius = 156.dp

/** The radius it gives way to before its hub moves off the Brush and Eraser pair to stay on screen. */
internal val PackWheelMinRadius = 128.dp

/** The hub cap at the rail edge. */
internal val PackWheelHubRadius = 22.dp

/** Where a name starts along its spoke, from the hub: clear of the cap, and of its neighbours. */
internal val PackWheelTextInner = 46.dp

/** Air between a name's far end and the rim. */
internal val PackWheelRimPad = 12.dp

/** The wheel keeps this far from the safe area's edges and from the layers rail. */
internal val PackWheelEdgeGap = 8.dp

/** What the wheel keeps clear of on the far side: the layers rail, its side inset and a gap. */
internal val PackWheelFarReserve = RailSideInset + RailWidth + PackWheelEdgeGap

/** Degrees between neighbouring spokes: seven names show across the half circle. */
internal const val PACK_WEDGE_DEGREES = 24f

/** How long after a commit the wheel stays up, so the pick can be seen landing, before it shuts. */
internal const val PACK_WHEEL_CLOSE_MS = 600L

/** Names fade out toward the rail edge and are not drawn past this angle from the pointer. */
private const val PACK_VISIBLE_DEGREES = 84f

private const val HALF_TURN = 180f
private const val DIM_ALPHA = 0.55f

/**
 * Whether the pack wheel is up, and where the Brush and Eraser items sit (root coordinates) that it
 * opens from. Plain UI state of the screen: nothing here reaches the view-model.
 */
@Stable
internal class PackWheelState {
    var open by mutableStateOf(false)
    var brush by mutableStateOf<Rect?>(null)
    var eraser by mutableStateOf<Rect?>(null)

    /** The disc on screen, root coordinates, published while placing; read by the outside-tap check. */
    internal var hubInRoot: Offset? = null
    internal var radiusPx: Float = 0f
    internal var opensRight: Boolean = true

    /**
     * Held up while the brush sheet is up (see [brushSheetUp]): the wheel stays open with the sheet,
     * so the post-commit shut, an outside press and a second tap on the tool do not close it.
     */
    var pinned by mutableStateOf(false)

    fun toggle() { if (open) close() else open = true }
    fun close() { if (!pinned) open = false }

    /** The sheet came up or went away: the wheel opens with it, or shuts with it. */
    fun pinTo(sheetUp: Boolean) {
        pinned = sheetUp
        if (sheetUp) open = true else open = false
    }

    fun anchor(tool: Tool, bounds: Rect) {
        when (tool) {
            Tool.BRUSH -> brush = bounds
            Tool.ERASER -> eraser = bounds
            else -> Unit
        }
    }

    /** Whether [p] (root coordinates) is on the half disc, or on the Brush or Eraser item. */
    fun owns(p: Offset): Boolean {
        val hub = hubInRoot
        return brush?.contains(p) == true || eraser?.contains(p) == true ||
            hub != null && onHalfDisc(p - hub, radiusPx, opensRight)
    }
}

/** Whether [fromHub] (an offset from the hub) is on the half disc of [radius] opening right or left. */
internal fun onHalfDisc(fromHub: Offset, radius: Float, opensRight: Boolean): Boolean =
    hypot(fromHub.x, fromHub.y) <= radius && (if (opensRight) fromHub.x >= 0f else fromHub.x <= 0f)

/**
 * Shuts the pack wheel on any press outside it. Installed on the screen's root, it watches presses
 * on their way down (the initial pass) and consumes nothing, so the press still does what it does.
 */
internal fun Modifier.closesPackWheelOutside(state: PackWheelState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (state.open && !state.owns(down.position)) state.close()
    }
}

/** Where the wheel goes: its hub (px, in the overlay) and radius (px), and which way it opens. */
internal data class PackWheelLayout(val hub: Offset, val radius: Float, val opensRight: Boolean)

/**
 * The wheel in an overlay [area] (the safe drawing area): its flat side on the rail's edge at
 * [railEdgeX], opening toward the canvas, centred on [anchorY] (between the Brush and the Eraser).
 * The radius is [maxRadius] when it fits, less to keep [farReserve] (the layers rail) and
 * [edgeGap] clear, down to [minRadius]; past that the hub slides along the rail to stay on screen.
 */
@Suppress("LongParameterList")
internal fun packWheelLayout(
    area: Size,
    railEdgeX: Float,
    anchorY: Float,
    opensRight: Boolean,
    maxRadius: Float,
    minRadius: Float,
    farReserve: Float,
    edgeGap: Float,
): PackWheelLayout {
    val outward = if (opensRight) area.width - railEdgeX - farReserve else railEdgeX - farReserve
    val vertical = min(anchorY - edgeGap, area.height - edgeGap - anchorY)
    val fit = (area.height - edgeGap * 2) / 2
    val radius = minOf(maxRadius, outward, max(vertical, minRadius), fit).coerceAtLeast(0f)
    val hubY = anchorY.coerceIn(edgeGap + radius, max(edgeGap + radius, area.height - edgeGap - radius))
    return PackWheelLayout(Offset(railEdgeX, hubY), radius, opensRight)
}

/** The pointer's spoke: straight out from the rail, horizontally. */
internal fun packPointerDegrees(opensRight: Boolean): Float = if (opensRight) 0f else HALF_TURN

/** +1 when later packs lie clockwise of the pointer; either hand, later packs lie below it. */
private fun packDirection(opensRight: Boolean) = if (opensRight) 1f else -1f

/** The spoke angle (degrees, screen space) of pack [index] while the wheel sits at [position]. */
internal fun packSpokeDegrees(index: Int, position: Float, opensRight: Boolean): Float =
    packPointerDegrees(opensRight) + packDirection(opensRight) * (index - position) * PACK_WEDGE_DEGREES

/** The fractional pack index at screen angle [degrees], with the wheel at [position]. */
internal fun packIndexAt(degrees: Float, position: Float, opensRight: Boolean): Float =
    position + packDirection(opensRight) * angleDelta(packPointerDegrees(opensRight), degrees) / PACK_WEDGE_DEGREES

/** [index] folded into `0 until count`: the wheel is a ring, so any integer names a pack. */
internal fun wrapIndex(index: Int, count: Int): Int = Math.floorMod(index, count)

/**
 * How far pack [index] lies from the wheel at [position], in packs, taking the short way round the
 * ring: always within half a turn of the pack count either side of the pointer.
 */
internal fun wrapOffset(index: Int, position: Float, count: Int): Float {
    val d = index - position
    return d - count * kotlin.math.round(d / count)
}

/**
 * The slots drawn with the wheel at [position]: whole-number places on the unbounded ring within
 * the drawing window of the pointer. A pack is [wrapIndex] of its slot, so with few packs the same
 * pack shows in more than one slot, and each slot moves continuously as the wheel turns.
 */
internal fun packVisibleSlots(position: Float): IntRange {
    val reach = PACK_VISIBLE_DEGREES / PACK_WEDGE_DEGREES
    return kotlin.math.ceil(position - reach).toInt()..kotlin.math.floor(position + reach).toInt()
}

/** The spoke angle of slot [slot] (an unwrapped place on the ring) with the wheel at [position]. */
internal fun packSlotDegrees(slot: Int, position: Float, opensRight: Boolean): Float =
    packSpokeDegrees(slot, position, opensRight)

/**
 * Where pack [index]'s accessibility node sits: its copy in `[-reach, count - reach)` of the
 * pointer, with the reach of the drawing window. It only changes copy at the window's edge, where
 * the name is not drawn, so it follows the name instead of crossing the disc.
 */
internal fun packNodeOffset(index: Int, position: Float, count: Int): Float {
    val reach = maxOf(count / 2f, PACK_VISIBLE_DEGREES / PACK_WEDGE_DEGREES)
    val d = index - position
    return d - count * kotlin.math.floor((d + reach) / count)
}

/** The spoke angle of pack [index] on the ring of [count] packs, with the wheel at [position]. */
internal fun packSpokeDegreesWrapped(index: Int, position: Float, count: Int, opensRight: Boolean): Float =
    packPointerDegrees(opensRight) + packDirection(opensRight) * wrapOffset(index, position, count) * PACK_WEDGE_DEGREES

/** The whole-number position a release at [position] moving at [velocity] (packs/s) lands on, unbounded. */
internal fun packSnapLanding(position: Float, velocity: Float): Int =
    (position + velocity * FLING_SECONDS).roundToInt()

/** The whole-number position, from [position], that reaches pack [index] by the shortest turn. */
internal fun packLandingFor(index: Int, position: Float, count: Int): Int =
    (position + wrapOffset(index, position, count)).roundToInt()

/** A name's rotation on spoke [degrees]: along the spoke, and never upside down on either hand. */
internal fun packTextRotation(degrees: Float, opensRight: Boolean): Float =
    if (opensRight) angleDelta(0f, degrees) else angleDelta(HALF_TURN, degrees)

/** A name's widest drawn length along its spoke, at [radius]: only a longer name gets an ellipsis. */
internal fun packNameMaxLength(radius: Float, density: Density): Float =
    radius - with(density) { (PackWheelTextInner + PackWheelRimPad).toPx() }

/** The half disc, flat side on the rail: the wheel's look and its hit area both. */
private class HalfDiscShape(private val opensRight: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = size.height / 2
        val hub = Offset(if (opensRight) 0f else size.width, r)
        return Outline.Generic(
            Path().apply {
                moveTo(hub.x, 0f)
                arcTo(Rect(hub, r), -90f, if (opensRight) 180f else -180f, forceMoveTo = false)
                close()
            },
        )
    }
}

/** Whether a pack's name had to be cut with an ellipsis to fit its spoke. */
internal val PackNameEllipsized = SemanticsPropertyKey<Boolean>("PackNameEllipsized")
private var SemanticsPropertyReceiver.packNameEllipsized by PackNameEllipsized

/** The wheel's radius in px, for tests. */
internal val PackWheelRadiusPx = SemanticsPropertyKey<Float>("PackWheelRadiusPx")
private var SemanticsPropertyReceiver.packWheelRadiusPx by PackWheelRadiusPx

/**
 * The brush pack wheel: a half disc whose flat side lies along the tool rail's edge, centred on the
 * Brush and Eraser pair and opening toward the canvas (mirrored for the left-handed). Every pack's
 * name lies on its own spoke, [PACK_WEDGE_DEGREES] apart across the half circle, Wheel-of-Fortune
 * style; the spoke pointing straight out from the rail, marked by the pointer, is the active pack.
 * Favorites is the first pack.
 *
 * Drag around it to turn it; a release flings and snaps to a spoke with a tick at each one. It turns
 * without end: past the last pack comes the first, and the other way round. A tap on a name commits
 * it at once; after a drag or fling the pointed
 * pack is provisional (outlined) until the wheel has rested on it for [WHEEL_DWELL_MS]. After a
 * commit the wheel shuts [PACK_WHEEL_CLOSE_MS] later. It also shuts when [closeWhen] turns true (a
 * stroke starting) and, through [closesPackWheelOutside], on a press anywhere off it.
 *
 * Only the half disc takes touches: the box around it is clipped to the disc's outline, which hit
 * testing follows. The rotation lives in a float state read only while drawing and placing, so a
 * drag recomposes nothing.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun RailPackWheel(
    state: PackWheelState,
    sets: List<CarouselTabGroup>,
    labels: Map<String, String>,
    selectedKey: String?,
    opensRight: Boolean,
    onPick: (CarouselTabGroup) -> Unit,
    modifier: Modifier = Modifier,
    closeWhen: () -> Boolean = { false },
) {
    val currentClose by rememberUpdatedState(closeWhen)
    LaunchedEffect(state) {
        snapshotFlow { currentClose() }.collect { if (it) state.close() }
    }
    val brush = state.brush
    if (!state.open || sets.isEmpty() || brush == null) return
    var origin by remember { mutableStateOf<Offset?>(null) }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val at = origin ?: return@BoxWithConstraints
        val density = LocalDensity.current
        val anchor = state.eraser?.let { (brush.center.y + it.center.y) / 2 } ?: brush.center.y
        val railHalf = with(density) { RailWidth.toPx() / 2 }
        val edge = if (opensRight) brush.center.x + railHalf else brush.center.x - railHalf
        val layout = with(density) {
            packWheelLayout(
                Size(maxWidth.toPx(), maxHeight.toPx()), edge - at.x, anchor - at.y, opensRight,
                PackWheelMaxRadius.toPx(), PackWheelMinRadius.toPx(), PackWheelFarReserve.toPx(),
                PackWheelEdgeGap.toPx(),
            )
        }
        state.hubInRoot = layout.hub + at
        state.radiusPx = layout.radius
        state.opensRight = opensRight
        PackWheelDisc(layout, sets, labels, selectedKey, onPick, onClose = state::close)
    }
}

@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
private fun PackWheelDisc(
    layout: PackWheelLayout,
    sets: List<CarouselTabGroup>,
    labels: Map<String, String>,
    selectedKey: String?,
    onPick: (CarouselTabGroup) -> Unit,
    onClose: () -> Unit,
) {
    val packWheelDescription = stringResource(R.string.pack_wheel_description)
    val selectLabel = stringResource(R.string.wheel_select_action)
    val opensRight = layout.opensRight
    val count = sets.size
    val selectedIndex = sets.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val position = remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentPick by rememberUpdatedState(onPick)
    val currentClose by rememberUpdatedState(onClose)
    val currentSets by rememberUpdatedState(sets)
    val currentSelected by rememberUpdatedState(selectedIndex)
    val motion = remember { PackMotion() }

    fun commit(index: Int) {
        currentSets.getOrNull(index)?.let(currentPick)
        motion.committed = index
    }

    // Turns to [target]. A tap commits at once (even the pack already pointed at: it brings the sheet
    // back on it); a drag or fling commits after the dwell, if it moved. Then, with [close], shuts.
    // [landing] is a position on the unbounded ring; the pack it names is [wrapIndex] of it.
    fun settleTo(landing: Int, commitNow: Boolean, close: Boolean = true) {
        motion.job?.cancel()
        val target = wrapIndex(landing, count)
        if (commitNow) commit(target)
        motion.job = scope.launch {
            val spec = spring<Float>(Spring.DampingRatioLowBouncy)
            animate(position.floatValue, landing.toFloat(), animationSpec = spec) { v, _ ->
                position.floatValue = v
            }
            position.floatValue = target.toFloat() // the same spot on the ring, kept small
            if (!commitNow) {
                delay(WHEEL_DWELL_MS)
                if (target != (motion.committed ?: currentSelected)) commit(target)
            }
            if (close) {
                delay(PACK_WHEEL_CLOSE_MS)
                currentClose()
            }
        }
    }

    // A pack picked elsewhere turns the wheel to it.
    LaunchedEffect(selectedIndex) {
        motion.committed = null
        val resting = wrapIndex(position.floatValue.roundToInt(), count)
        if (!motion.dragging && motion.job?.isActive != true && resting != selectedIndex) {
            position.floatValue = selectedIndex.toFloat()
        }
    }
    LaunchedEffect(count) {
        var last: Int? = null
        snapshotFlow { wrapIndex(position.floatValue.roundToInt(), count) }.collect { i ->
            if (last != null && last != i) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            last = i
        }
    }

    val density = LocalDensity.current
    val radius = layout.radius
    val hub = Offset(if (opensRight) 0f else radius, radius)
    val textIn = with(density) { PackWheelTextInner.toPx() }
    val nameMax = packNameMaxLength(radius, density)
    val capPx = with(density) { PackWheelHubRadius.toPx() }
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val baseStyle = MaterialTheme.typography.labelLarge
    val layouts: List<Pair<TextLayoutResult, TextLayoutResult>> = remember(sets, labels, nameMax, baseStyle) {
        sets.map { set ->
            val text = labels[set.key] ?: set.label
            fun measure(bold: Boolean) = measurer.measure(
                text, if (bold) baseStyle.copy(fontWeight = FontWeight.Bold) else baseStyle,
                overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = nameMax.toInt().coerceAtLeast(0)),
            )
            measure(false) to measure(true)
        }
    }
    val pointer = packPointerDegrees(opensRight)
    fun angleOf(p: Offset) = (atan2(p.y - hub.y, p.x - hub.x) * HALF_TURN / PI).toFloat()
    fun onDisc(p: Offset) = onHalfDisc(p - hub, radius, opensRight)
    fun move(by: Int) {
        val from = motion.committed ?: currentSelected
        val target = wrapIndex(from + by, count)
        // Stepped through with TalkBack: commits each, and stays up for the next step.
        if (target != from) {
            settleTo(packLandingFor(target, position.floatValue, count), commitNow = true, close = false)
        }
    }
    val boxPx = IntOffset(
        (layout.hub.x - if (opensRight) 0f else radius).roundToInt(),
        (layout.hub.y - radius).roundToInt(),
    )
    val sizeDp = with(density) { radius.toDp() }
    val discShape = remember(opensRight) { HalfDiscShape(opensRight) }

    Box(
        Modifier
            .offset { boxPx }
            .size(sizeDp, sizeDp * 2)
            // Clipped to the half disc: hit testing follows the outline, so the box's corners pass
            // touches through to whatever is under them.
            .graphicsLayer {
                clip = true
                shape = discShape
            }
            .testTag("rail.packwheel")
            .semantics {
                selectableGroup()
                contentDescription = packWheelDescription
                packWheelRadiusPx = radius
                collectionInfo = CollectionInfo(rowCount = count, columnCount = 1)
                customActions = listOf(
                    CustomAccessibilityAction("Next pack") { move(1); true },
                    CustomAccessibilityAction("Previous pack") { move(-1); true },
                )
            }
            .pointerInput(count, opensRight, radius) {
                val tracker = VelocityTracker1D(isDataDifferential = false)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!onDisc(down.position)) return@awaitEachGesture
                    var lastAngle = angleOf(down.position)
                    val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    motion.job?.cancel()
                    motion.dragging = true
                    tracker.resetTracking()
                    fun turn(change: PointerInputChange) {
                        val a = angleOf(change.position)
                        val turned = angleDelta(lastAngle, a)
                        lastAngle = a
                        change.consume()
                        // The names follow the finger round the hub.
                        val delta = -packDirection(opensRight) * turned / PACK_WEDGE_DEGREES
                        position.floatValue += delta
                        tracker.addDataPoint(change.uptimeMillis, position.floatValue)
                    }
                    turn(slop)
                    val completed = drag(slop.id) { turn(it) }
                    motion.dragging = false
                    val v = if (completed) tracker.calculateVelocity() else 0f
                    settleTo(packSnapLanding(position.floatValue, if (v.isFinite()) v else 0f), commitNow = false)
                }
            }
            .pointerInput(count, opensRight, radius) {
                detectTapGestures { tap ->
                    if (!onDisc(tap) || hypot(tap.x - hub.x, tap.y - hub.y) < capPx) return@detectTapGestures
                    settleTo(
                        packIndexAt(angleOf(tap), position.floatValue, opensRight).roundToInt(),
                        commitNow = true,
                    )
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pos = position.floatValue
            val pointed = wrapIndex(pos.roundToInt(), count)
            val committed = pointed == selectedIndex
            val start = if (opensRight) -90f else 90f
            // The disc, the pointed wedge (filled once committed, outlined while provisional), the
            // spokes between names, the rim, the hub cap and the pointer.
            drawCircle(colors.surface.copy(alpha = 0.94f), radius, hub)
            val wedge = Path().apply {
                moveTo(hub.x, hub.y)
                arcTo(Rect(hub, radius), pointer - PACK_WEDGE_DEGREES / 2, PACK_WEDGE_DEGREES, forceMoveTo = false)
                close()
            }
            if (committed) {
                drawPath(wedge, colors.primary.copy(alpha = 0.26f))
            } else {
                drawPath(wedge, colors.primary, style = Stroke(1.5.dp.toPx()))
            }
            val hair = 1.dp.toPx()
            // The spokes between names, turning with them.
            for (k in -5..4) {
                val a = packSlotDegrees(pos.roundToInt(), pos, opensRight) + (k + 0.5f) * PACK_WEDGE_DEGREES
                if (abs(angleDelta(pointer, a)) > 90f) continue
                val rad = a * PI.toFloat() / HALF_TURN
                drawLine(
                    colors.outlineVariant,
                    hub + Offset(capPx * cos(rad), capPx * sin(rad)),
                    hub + Offset(radius * cos(rad), radius * sin(rad)),
                    strokeWidth = hair,
                )
            }
            drawArc(
                colors.outline, start, 180f, false, hub - Offset(radius - hair / 2, radius - hair / 2),
                Size((radius - hair / 2) * 2, (radius - hair / 2) * 2), style = Stroke(hair),
            )
            drawCircle(colors.surfaceVariant, capPx, hub)
            drawCircle(colors.outline, capPx, hub, style = Stroke(hair))
            // The pointer: a notch on the rim, pointing in along the active spoke.
            val tip = 8.dp.toPx()
            val out = if (opensRight) 1f else -1f
            val rimX = hub.x + out * radius
            drawPath(
                Path().apply {
                    moveTo(rimX, hub.y - tip)
                    lineTo(rimX, hub.y + tip)
                    lineTo(rimX - out * tip * 1.4f, hub.y)
                    close()
                },
                colors.primary,
            )
            // And a short bar from the cap, so the pointed spoke reads from the hub out.
            drawLine(
                colors.primary, Offset(hub.x + out * capPx, hub.y), Offset(hub.x + out * (textIn - 6.dp.toPx()), hub.y),
                strokeWidth = 3.dp.toPx(),
            )
            for (slot in packVisibleSlots(pos)) {
                val (plain, bold) = layouts[wrapIndex(slot, count)]
                val spoke = packSlotDegrees(slot, pos, opensRight)
                val off = abs(angleDelta(pointer, spoke))
                if (off > PACK_VISIBLE_DEGREES) continue
                val lit = (slot - pos) in -0.5f..0.5f
                val text = if (lit) bold else plain
                val fade = (1f - (off / PACK_VISIBLE_DEGREES).let { it * it } * 0.6f)
                val color = if (lit) colors.onSurface else colors.onSurface.copy(alpha = DIM_ALPHA * fade)
                rotate(packTextRotation(spoke, opensRight), pivot = hub) {
                    val x = if (opensRight) hub.x + textIn else hub.x - textIn - text.size.width
                    drawText(text, color = color, topLeft = Offset(x, hub.y - text.size.height / 2f))
                }
            }
        }
        // One selectable node per pack, riding on its name, for accessibility services. Placement
        // only, so turning the wheel recomposes nothing; touches pass through to the wheel.
        val itemSize = 28.dp
        val itemPx = with(density) { itemSize.toPx() }
        val itemR = textIn + nameMax / 2
        sets.forEachIndexed { i, set ->
            val label = labels[set.key] ?: set.label
            val cut = layouts[i].second.isLineEllipsized(0)
            Box(
                Modifier
                    .offset {
                        val deg = packPointerDegrees(opensRight) +
                            packDirection(opensRight) * packNodeOffset(i, position.floatValue, count) *
                            PACK_WEDGE_DEGREES
                        val spoke = deg * (PI / HALF_TURN).toFloat()
                        IntOffset(
                            (hub.x + itemR * cos(spoke) - itemPx / 2).roundToInt(),
                            (hub.y + itemR * sin(spoke) - itemPx / 2).roundToInt(),
                        )
                    }
                    .size(itemSize)
                    .semantics {
                        text = AnnotatedString(label)
                        selected = i == selectedIndex
                        role = Role.Tab
                        collectionItemInfo = CollectionItemInfo(i, 1, 0, 1)
                        packNameEllipsized = cut
                        onClick(label = selectLabel) {
                            settleTo(packLandingFor(i, position.floatValue, count), commitNow = true)
                            true
                        }
                    }
                    .testTag("rail.packwheel.item"),
            )
        }
    }
}

/** The wheel's running motion: the turn (and dwell, and close) in progress, and a finger on it. */
private class PackMotion {
    var job: Job? = null
    var dragging = false

    /** A pack committed that the caller has not yet handed back as selected. */
    var committed: Int? = null
}
