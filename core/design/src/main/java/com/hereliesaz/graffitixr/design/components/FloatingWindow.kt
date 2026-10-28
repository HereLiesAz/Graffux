// FILE: core/design/src/main/java/com/hereliesaz/graffitixr/design/components/FloatingWindow.kt
package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The horizontal strip of the screen the tool rail currently occupies. Every [FloatingWindow]
 * keeps clear of it — both where it first appears and wherever it settles after being dragged —
 * since a panel spawning under, or later dragged under, the rail is unreachable there: the rail
 * always draws on top.
 *
 * Provide the real value once near the editor's root, where the rail is docked (see `MainActivity`).
 * The default assumes no rail is docked, so a host that never provides one still gets ordinary
 * onscreen clamping.
 */
data class RailInset(val dockedOnLeft: Boolean, val width: Dp)

val LocalRailInset = compositionLocalOf { RailInset(dockedOnLeft = false, width = 0.dp) }

private val MaxWindowWidth = 320.dp
private val MaxWindowHeight = 480.dp

// [clampFullyOnscreen]'s `containerSize` (from LocalWindowInfo) is the full display area, not the
// safe-content area clear of system bars -- a window whose available space is fully used (this
// file's own mount-time correction pinning `maxY` exactly to `containerSize.height`) can end up with its bottom edge sitting right under the gesture nav bar,
// content there unreachable even though the window is technically "fully onscreen" by the numbers.
// A modest inset from every edge sidesteps this without needing precise WindowInsets plumbing here.
private val SafeEdgeMargin = 24.dp

/**
 * Where a [FloatingWindow] sits, in window pixels. Hoist one via [rememberFloatingWindowState] when
 * a window's placement has to outlive the window itself.
 */
@Stable
class FloatingWindowState(offsetX: Float, offsetY: Float) {
    var offsetX by mutableFloatStateOf(offsetX)
        private set
    var offsetY by mutableFloatStateOf(offsetY)
        private set

    fun moveTo(x: Float, y: Float) {
        offsetX = x
        offsetY = y
    }

    fun dragBy(dx: Float, dy: Float) = moveTo(offsetX + dx, offsetY + dy)
}

/**
 * A floating, draggable panel for tool options — Procreate-style, in place of a modal
 * [androidx.compose.material3.AlertDialog]. The header bar is the drag handle; unlike a dialog it
 * never dims or blocks the canvas, so several can be open over the artwork at once.
 *
 * It keeps clear of the rail ([LocalRailInset]) and fully onscreen: when it first appears and again
 * whenever a drag ends. Every call site shares [FloatingWindowStacking] through the same un-keyed
 * defaults, so a window opened after another lands on top of it and a step down and right of it.
 *
 * [initialOffset] seeds the position once; pass a hoisted [state] to keep a placement across closes.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
fun FloatingWindow(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset(60f, 160f),
    state: FloatingWindowState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Claimed once per window instance: re-runs only when the window is (re)opened.
    val stackSlot = remember { FloatingWindowStacking.claim() }
    val internalState = remember {
        val seed = initialOffset + stackSlot.cascadeOffset
        FloatingWindowState(seed.x, seed.y)
    }
    val windowState = state ?: internalState

    val railInset = LocalRailInset.current
    val density = LocalDensity.current
    val containerSize = LocalWindowInfo.current.containerSize
    val obstruction = remember(containerSize, railInset, density) {
        railInset.asObstruction(containerSize, density)
    }
    var windowSize by remember { mutableStateOf<IntSize?>(null) }
    val marginPx = with(density) { SafeEdgeMargin.toPx() }
    val settle: () -> Unit = {
        windowSize?.let { size ->
            val safe = clampFullyOnscreen(
                Offset(windowState.offsetX, windowState.offsetY),
                Offset(size.width.toFloat(), size.height.toFloat()),
                containerSize,
                obstruction,
                marginPx,
            )
            if (safe.x != windowState.offsetX || safe.y != windowState.offsetY) windowState.moveTo(safe.x, safe.y)
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .zIndex(stackSlot.zIndex)
            .offset { IntOffset(windowState.offsetX.roundToInt(), windowState.offsetY.roundToInt()) }
            .widthIn(min = 220.dp, max = MaxWindowWidth)
            .heightIn(max = MaxWindowHeight)
            .onSizeChanged { windowSize = it }
            // Semi-transparent: the panel sits over the artwork, and a canvas faintly visible through
            // it keeps the user oriented while it is up.
            .background(colors.surface.copy(alpha = 0.85f))
            .border(1.dp, colors.outlineVariant)
            // Taps inside the window never fall through to the canvas.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(TitleBarHeight)
                .background(colors.outlineVariant.copy(alpha = 0.35f))
                .pointerInput(windowState) {
                    detectDragGestures(onDragEnd = settle, onDragCancel = settle) { change, drag ->
                        change.consume()
                        windowState.dragBy(drag.x, drag.y)
                    }
                }
                .padding(start = 12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(TitleBarHeight)
                    .clickable(onClickLabel = "Close $title", onClick = onDismiss)
                    .semantics { contentDescription = "Close" },
            ) {
                Text("✕", color = colors.onSurface, style = MaterialTheme.typography.titleSmall)
            }
        }
        // Scrolls rather than crushing: a plain Column capped at MaxWindowHeight hands its last
        // child whatever is left, which can be ~0px.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            content = content,
        )
    }

    // A newly (re)opened window is pulled fully onscreen and clear of the rail at once, with no
    // drag required.
    LaunchedEffect(containerSize, railInset) {
        snapshotFlow { windowSize }.filterNotNull().first()
        settle()
    }
}

private val TitleBarHeight = 36.dp

/** [RailInset] as a container-space [Rect]. */
private fun RailInset.asObstruction(containerSize: IntSize, density: Density): Rect? {
    if (width == 0.dp || containerSize.width <= 0 || containerSize.height <= 0) return null
    val widthPx = with(density) { width.toPx() }
    val height = containerSize.height.toFloat()
    return if (dockedOnLeft) Rect(0f, 0f, widthPx, height)
    else Rect(containerSize.width - widthPx, 0f, containerSize.width.toFloat(), height)
}

/**
 * Clamps a top-left position so the whole `size` footprint fits inside the container and clear of
 * `obstruction`: only the edge(s) `obstruction` actually touches are enforced, since there's no side to define "clear of it" from
 * for a rect that touches none of the container's edges.
 *
 * [RailInset.asObstruction] only ever produces a full-HEIGHT strip (a left- or right-docked rail)
 * or a full-WIDTH strip (a top- or bottom-docked one) — never a rect floating clear of every edge.
 * A full-height strip touches `top` (0) and `bottom` (containerHeight) by construction, regardless
 * of which side it's docked on — checking "does it touch top/bottom" in isolation to decide whether
 * to narrow the vertical range therefore fired for a purely-horizontal (left/right) obstruction too,
 * which does not touch the top or bottom edge in any y-clamping sense; it just happens to span the
 * full height. That silently pushed `minY` all the way to `obstruction.bottom` — the full container
 * height — for every left-docked rail, so a freshly-opened window's "safe" position became fully
 * off-screen (`y = containerHeight`) instead of merely clear of the rail on the x-axis. This is what
 * made a floating window (the colour picker among them) render once at its raw mount position, get
 * "corrected" straight off the bottom edge, and never come back — not a dismiss (`onDismiss()` is
 * never called by this path), so the state backing it stayed "open" the whole time.
 *
 * Fixed by classifying the obstruction first — a full-height strip only ever narrows X, a full-width
 * strip only ever narrows Y — rather than testing each of the four edges independently.
 *
 * [marginPx] insets all four container edges (not the obstruction's own edge, which needs no such
 * buffer — the window is meant to sit flush against it) — see [SafeEdgeMargin]'s doc comment.
 */
private fun clampFullyOnscreen(topLeft: Offset, size: Offset, containerSize: IntSize, obstruction: Rect?, marginPx: Float = 0f): Offset {
    if (containerSize.width <= 0 || containerSize.height <= 0) return topLeft
    var minX = marginPx
    var maxX = (containerSize.width - marginPx - size.x).coerceAtLeast(minX)
    var minY = marginPx
    var maxY = (containerSize.height - marginPx - size.y).coerceAtLeast(minY)
    if (obstruction != null) {
        val isVerticalBar = obstruction.top <= 0f && obstruction.bottom >= containerSize.height
        val isHorizontalBar = obstruction.left <= 0f && obstruction.right >= containerSize.width
        if (isVerticalBar) {
            if (obstruction.left <= 0f) minX = minX.coerceAtLeast(obstruction.right)
            if (obstruction.right >= containerSize.width) maxX = maxX.coerceAtMost(obstruction.left - size.x)
        }
        if (isHorizontalBar) {
            if (obstruction.top <= 0f) minY = minY.coerceAtLeast(obstruction.bottom)
            if (obstruction.bottom >= containerSize.height) maxY = maxY.coerceAtMost(obstruction.top - size.y)
        }
        if (minX > maxX) maxX = minX
        if (minY > maxY) maxY = minY
    }
    return Offset(topLeft.x.coerceIn(minX, maxX), topLeft.y.coerceIn(minY, maxY))
}

/**
 * Position for a [FloatingWindow], for the caller that needs it to survive the window closing. `remember`ed
 * against [initialOffset], so a window given a fresh starting position
 * takes it rather than silently keeping the first one it was ever handed.
 */
@Composable
fun rememberFloatingWindowState(initialOffset: Offset = Offset(60f, 160f)): FloatingWindowState =
    remember(initialOffset) { FloatingWindowState(initialOffset.x, initialOffset.y) }

/**
 * Assigns each [FloatingWindow] a place in the open-order stack: a z-index higher than every window
 * currently open, and a small cascade offset so consecutively-opened windows step diagonally rather
 * than landing exactly on top of one another. Not Compose state — read once per window instance via
 * `remember {}`, so claiming a slot never itself triggers recomposition.
 */
private object FloatingWindowStacking {
    class Slot(val zIndex: Float, val cascadeOffset: Offset)

    private const val BASE_Z = 10f
    private const val CASCADE_STEPS = 6
    private const val CASCADE_STEP_PX = 28f

    private var windowsOpened = 0

    fun claim(): Slot {
        val ordinal = windowsOpened++
        val step = ordinal % CASCADE_STEPS
        return Slot(
            zIndex = BASE_Z + ordinal + 1,
            cascadeOffset = Offset(step * CASCADE_STEP_PX, step * CASCADE_STEP_PX),
        )
    }
}
