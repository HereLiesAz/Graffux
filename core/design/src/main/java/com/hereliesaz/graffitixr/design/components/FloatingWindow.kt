// FILE: core/design/src/main/java/com/hereliesaz/graffitixr/design/components/FloatingWindow.kt
package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.hereliesaz.aznavrail.AzWindow
import com.hereliesaz.aznavrail.AzWindowState
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The horizontal strip of the screen the AzNavRail rail currently occupies. Every [FloatingWindow]
 * keeps clear of it — both where it first appears and wherever it settles after being dragged —
 * since a panel spawning under, or later dragged under, the rail is unreachable there: the rail
 * always draws on top.
 *
 * Provide the real value once near the editor's root, alongside the same `azConfig(dockingSide =
 * ...)` call that docks the rail itself — see `MainActivity`'s `measuredRailWidth`, read back from
 * `AzNavHostScope.railWidth` rather than guessed, since the rail's actual on-screen width is a
 * separate config knob (`collapsedWidth`) from its per-button width. The default assumes no rail is
 * docked, so a host that never provides one still gets ordinary onscreen clamping.
 */
data class RailInset(val dockedOnLeft: Boolean, val width: Dp)

val LocalRailInset = compositionLocalOf { RailInset(dockedOnLeft = false, width = 0.dp) }

private val MaxWindowWidth = 320.dp
private val MaxWindowHeight = 480.dp

// [clampFullyOnscreen]'s `containerSize` (from LocalWindowInfo) is the full display area, not the
// safe-content area clear of system bars -- a window whose available space is fully used (this
// file's own mount-time correction, or AzWindow's internal one before it, pinning `maxY` exactly to
// `containerSize.height`) can end up with its bottom edge sitting right under the gesture nav bar,
// content there unreachable even though the window is technically "fully onscreen" by the numbers.
// A modest inset from every edge sidesteps this without needing precise WindowInsets plumbing here.
private val SafeEdgeMargin = 24.dp

// A window more than this fraction offscreen is being thrown away, not just moved — closing it
// beats leaving a barely-there sliver nobody can find their way back to. AzWindow itself has no
// notion of this; it only ever guarantees a minimal sliver stays reachable.
private const val CLOSE_OFFSCREEN_FRACTION = 0.9f

/**
 * A floating, draggable, collapsible panel for tool options — Procreate-style, in place of a modal
 * [androidx.compose.material3.AlertDialog]. The header bar is the drag handle; unlike a dialog it
 * never dims or blocks the canvas, so several can be open over the artwork at once, each positioned
 * and collapsed independently.
 *
 * **This is a thin wrapper over AzNavRail's [AzWindow]** (11.19+). Three things it adds on top:
 *
 *  - **Rail avoidance and full-onscreen settling**, via [AzWindow]'s own `obstruction` and
 *    `snapFullyOnscreen` params — built from [LocalRailInset] here, so a window can never be
 *    dragged (or settle) under the rail, and always ends up fully back onscreen once a drag ends,
 *    not just leaving a minimal sliver reachable.
 *  - **A newly-(re)opened window is corrected too**, not just a dragged one: [AzWindow]'s own
 *    mount-time clamp only guarantees that same minimal sliver, so this wrapper additionally pulls
 *    a freshly-placed window fully onscreen and clear of the rail right after its first layout,
 *    using the now-public [AzWindowState.moveTo] — no drag required to reach a safe position.
 *  - **Closing past [CLOSE_OFFSCREEN_FRACTION] offscreen** — a "drag it away to dismiss" gesture
 *    AzWindow doesn't offer on its own — by watching the window's own reported bounds live and
 *    dismissing the moment they cross the threshold, mid-drag.
 *
 * The signature is deliberately unchanged, so the two dozen call sites did not have to move. What
 * they get for free: the clamp, a fold control that matches every other AzNavRail surface, and an
 * accent that follows the rail's rather than being independently themed here — plus a place in the
 * stack: every call site shares [FloatingWindowStacking] through the same un-keyed defaults, so a
 * window opened after another always lands on top of it and doesn't start at the exact same point
 * on screen. Before this, all two dozen call sites defaulted to the identical fixed position and
 * z-index, so a just-opened window could render invisibly underneath an older one with no hint it
 * had opened at all — Compose breaks a tie between equal z-indices by composition order, which has
 * nothing to do with which window the user actually asked for most recently.
 *
 * [initialOffset] seeds the position once. Hoist an [AzWindowState] via [rememberFloatingWindowState]
 * and pass it as [state] when a window's placement or folded state has to outlive the window itself
 * — a hoisted state still gets the same rail-avoidance/onscreen correction, since [AzWindowState]
 * exposes those operations publicly regardless of who created it.
 */
@Composable
fun FloatingWindow(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffset: Offset = Offset(60f, 160f),
    state: AzWindowState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Claimed once per window instance: remember with no keys re-runs only when this call site
    // re-enters composition, i.e. exactly when the window is (re)opened — not on every recomposition
    // while it stays open, and not shared with any other window's instance of this same call site.
    val stackSlot = remember { FloatingWindowStacking.claim() }
    val internalState = remember {
        val seed = initialOffset + stackSlot.cascadeOffset
        AzWindowState(seed.x, seed.y, false)
    }
    val windowState = state ?: internalState

    val railInset = LocalRailInset.current
    val density = LocalDensity.current
    val containerSize = LocalWindowInfo.current.containerSize
    val obstruction = remember(containerSize, railInset, density) {
        railInset.asObstruction(containerSize, density)
    }

    var liveRect by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            liveRect = Rect(coordinates.positionInWindow(), coordinates.size.toSize())
        }
    ) {
        AzWindow(
            modifier = modifier
                .zIndex(stackSlot.zIndex)
                .widthIn(min = 220.dp, max = MaxWindowWidth)
                .heightIn(max = MaxWindowHeight),
            title = title,
            state = windowState,
            // Not the rail's accent by default here: these panels sit over the artwork, and the rail's
            // accent is tuned to read against the rail rather than against whatever the user is painting.
            accent = MaterialTheme.colorScheme.outlineVariant,
            surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            onDismiss = onDismiss,
            obstruction = obstruction,
            snapFullyOnscreen = true,
        ) {
            // AzWindow caps its own height at MaxWindowHeight, but does not scroll content that
            // exceeds it -- a plain Column measured that way gives every earlier child its full
            // requested size and hands the last child whatever's left, which can be ~0px (this is
            // how the Color picker's own Apply button was found rendered 1px tall: the wheel, tabs
            // and slider above it already consumed nearly the entire 480dp budget). Scrolling here
            // means content that doesn't fit is reachable, not silently crushed.
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                content = content,
            )
        }
    }

    // Mount-time correction, THEN — only then — start watching for a real drag taking the window
    // (almost) entirely offscreen. One sequential effect, not two independent ones.
    //
    // Two distinct bugs lived here, found by instrumenting this effect against a real device:
    //
    // 1. AzWindow is hard evidence that the SAME obstruction (`obstruction`, passed to AzWindow
    //    directly below) drives its own internal mount-time "minimal sliver" clamp independently of
    //    anything in this file. Logging `windowState.offsetX`/`offsetY` immediately after mount —
    //    with this effect's own `moveTo()` call disabled entirely — still showed `offsetY` already
    //    corrupted to the full container height (window pushed off the bottom edge) for a window
    //    opened next to a left-docked, full-height rail. AzNavRail's own internal obstruction-
    //    narrowing logic (`AzWindowState`'s `narrowForObstruction`/`onPositioned`, per this file's
    //    own earlier doc comment claiming to mirror it) applies the identical bug this file's
    //    [clampFullyOnscreen] used to have: a full-height obstruction touches y=0 and y=height by
    //    construction regardless of which side it's docked on, and treating that alone as "this
    //    obstruction constrains the vertical range" pushes every window mounted next to a rail
    //    towards the bottom edge instead of merely clear of the rail horizontally. This is a bug to
    //    report/fix upstream in AzNavRail; it cannot be fixed from here.
    //
    // 2. This file's OWN correction was computing its delta from `liveRect.topLeft` (measured via
    //    `onGloballyPositioned` on the wrapping [Box] above) and adding it to `windowState.offsetX`/
    //    `offsetY`. But `liveRect` never tracked AzWindow's actual rendered position at all — it
    //    stayed pinned at (0, 0) across every observed frame regardless of what `windowState`'s
    //    fields held (AzWindow evidently repositions its content by some means `onGloballyPositioned`
    //    on an ancestor Box doesn't see, e.g. a graphics-layer translation applied inside AzWindow's
    //    own tree rather than a layout-level offset the wrapping Box's own measured bounds would
    //    move with). Adding a `liveRect`-derived delta to `windowState.offsetX`/`offsetY` therefore
    //    computed a value with no relation to where the window was actually sitting, compounding
    //    on top of bug 1's already-wrong offsetY rather than correcting it.
    //
    // Fixed by reading and writing `windowState.offsetX`/`offsetY` directly — the one value AzWindow
    // actually renders from — as an ABSOLUTE target (not a delta against the unreliable `liveRect`),
    // computed via this file's own (correctly axis-aware) [clampFullyOnscreen], using `liveRect`
    // only for its WIDTH/HEIGHT (size is unaffected by the position-tracking mismatch above). This
    // runs unconditionally, not just when a raw mismatch is detected, specifically because bug 1
    // already corrupts `offsetX`/`offsetY` before this effect ever gets to inspect them.
    //
    // The auto-dismiss watcher below still reads `liveRect`, which the finding above means may not
    // reflect a real drag's true resting position either — a known follow-up, not fixed here since
    // no evidence yet shows it firing incorrectly (this session's traces all show `willDismiss=false`
    // exactly when the window is, per the fix above, actually onscreen).
    LaunchedEffect(containerSize, railInset) {
        val rect = snapshotFlow { liveRect }.filterNotNull().first()
        val marginPx = with(density) { SafeEdgeMargin.toPx() }
        val safe = clampFullyOnscreen(
            Offset(windowState.offsetX, windowState.offsetY),
            Offset(rect.width, rect.height),
            containerSize,
            obstruction,
            marginPx,
        )
        if (safe.x != windowState.offsetX || safe.y != windowState.offsetY) {
            windowState.moveTo(safe.x, safe.y)
        }

        snapshotFlow { liveRect }
            .filterNotNull()
            .debounce(150)
            .collect { r ->
                if (visibleFraction(r, containerSize) <= 1f - CLOSE_OFFSCREEN_FRACTION) onDismiss()
            }
    }
}

/** [RailInset] as a container-space [Rect], matching what [AzWindow]'s `obstruction` param expects. */
private fun RailInset.asObstruction(containerSize: IntSize, density: Density): Rect? {
    if (width == 0.dp || containerSize.width <= 0 || containerSize.height <= 0) return null
    val widthPx = with(density) { width.toPx() }
    val height = containerSize.height.toFloat()
    return if (dockedOnLeft) Rect(0f, 0f, widthPx, height)
    else Rect(containerSize.width - widthPx, 0f, containerSize.width.toFloat(), height)
}

/** Fraction of `rect`'s own area that overlaps the container — 1f fully onscreen, 0f fully off. */
private fun visibleFraction(rect: Rect, containerSize: IntSize): Float {
    val visibleLeft = maxOf(rect.left, 0f)
    val visibleTop = maxOf(rect.top, 0f)
    val visibleRight = minOf(rect.right, containerSize.width.toFloat())
    val visibleBottom = minOf(rect.bottom, containerSize.height.toFloat())
    val visibleArea = (visibleRight - visibleLeft).coerceAtLeast(0f) * (visibleBottom - visibleTop).coerceAtLeast(0f)
    val totalArea = (rect.width * rect.height).coerceAtLeast(1f)
    return visibleArea / totalArea
}

/**
 * Clamps a top-left position so the whole `size` footprint fits inside the container and clear of
 * `obstruction` — mirroring [AzWindowState]'s own internal `narrowForObstruction`: only the edge(s)
 * `obstruction` actually touches are enforced, since there's no side to define "clear of it" from
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
 * Position + folded state for a [FloatingWindow], for the caller that needs it to survive the
 * window closing. `remember`ed against [initialOffset], so a window given a fresh starting position
 * takes it rather than silently keeping the first one it was ever handed.
 */
@Composable
fun rememberFloatingWindowState(initialOffset: Offset = Offset(60f, 160f)): AzWindowState =
    remember(initialOffset) { AzWindowState(initialOffset.x, initialOffset.y, false) }

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
