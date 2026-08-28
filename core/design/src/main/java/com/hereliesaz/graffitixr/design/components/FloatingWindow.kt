// FILE: core/design/src/main/java/com/hereliesaz/graffitixr/design/components/FloatingWindow.kt
package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import kotlinx.coroutines.flow.drop
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
            Column(modifier = Modifier.padding(12.dp), content = content)
        }
    }

    // Auto-close a window dragged (almost) entirely off screen — reacts live, mid-drag, so this
    // isn't waiting for a drag to end before it can act (unlike the onscreen-correction below,
    // which only matters once a position settles).
    //
    // Debounced, not just `.drop(1)`-ed, because more than one mount-time layout pass can land
    // before anything settles: AzWindow does its OWN internal onGloballyPositioned-driven clamp
    // (`AzWindowState.onPositioned`, a "minimal sliver" guarantee, separate from and prior to the
    // onscreen-correction effect below), and several [FloatingWindow]s cascade their initial
    // position diagonally toward the corner — so a window whose content is tall enough to want more
    // than `MaxWindowHeight` (the colour picker's wheel-plus-tabs-plus-swatches easily does), opened
    // as the Nth cascaded window, can measure two or more distinct raw/sliver-clamped frames before
    // the correction effect ever gets to fully place it onscreen, and a fixed `.drop(1)` only ever
    // accounts for exactly one of them. Evaluating an intermediate, not-yet-corrected frame here
    // read as a drag that had already finished, so the window dismissed itself the instant it
    // opened, before the user had touched it — and every reopen re-ran the same race, so it never
    // seemed to come back. Waiting for `liveRect` to stop changing for a beat means this only ever
    // judges a position the window is actually resting at — after every mount-time settle/correction
    // pass, or after the user's finger stops moving — never one it merely passed through.
    LaunchedEffect(containerSize) {
        snapshotFlow { liveRect }
            .filterNotNull()
            .debounce(150)
            .drop(1)
            .collect { rect ->
                if (visibleFraction(rect, containerSize) <= 1f - CLOSE_OFFSCREEN_FRACTION) onDismiss()
            }
    }

    // AzWindow's own mount-time clamp only guarantees the same minimal sliver a live drag does —
    // not full containment, which only kicks in once a drag actually settles (`snapFullyOnscreen`
    // above). Apply that same full correction once up front too, so a freshly-(re)opened window
    // never has to wait for a drag before it's somewhere reachable.
    LaunchedEffect(containerSize, railInset) {
        val rect = snapshotFlow { liveRect }.filterNotNull().first()
        val safe = clampFullyOnscreen(rect.topLeft, Offset(rect.width, rect.height), containerSize, obstruction)
        if (safe != rect.topLeft) {
            windowState.moveTo(
                windowState.offsetX + (safe.x - rect.left),
                windowState.offsetY + (safe.y - rect.top),
            )
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
 */
private fun clampFullyOnscreen(topLeft: Offset, size: Offset, containerSize: IntSize, obstruction: Rect?): Offset {
    if (containerSize.width <= 0 || containerSize.height <= 0) return topLeft
    var minX = 0f
    var maxX = (containerSize.width - size.x).coerceAtLeast(minX)
    var minY = 0f
    var maxY = (containerSize.height - size.y).coerceAtLeast(minY)
    if (obstruction != null) {
        if (obstruction.left <= 0f) minX = minX.coerceAtLeast(obstruction.right)
        if (obstruction.right >= containerSize.width) maxX = maxX.coerceAtMost(obstruction.left - size.x)
        if (obstruction.top <= 0f) minY = minY.coerceAtLeast(obstruction.bottom)
        if (obstruction.bottom >= containerSize.height) maxY = maxY.coerceAtMost(obstruction.top - size.y)
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
