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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.hereliesaz.aznavrail.AzWindow
import com.hereliesaz.aznavrail.AzWindowState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapNotNull

/**
 * The horizontal strip of the screen the AzNavRail rail currently occupies. Every [FloatingWindow]
 * keeps clear of it — both where it first appears and wherever it settles after being dragged —
 * since a panel spawning under, or later dragged under, the rail is unreachable there: the rail
 * always draws on top.
 *
 * Provide the real value once near the editor's root, alongside the same `azConfig(dockingSide =
 * ..., railItemWidth = ...)` call that docks the rail itself. The default assumes no rail is
 * docked, so a host that never provides one still gets ordinary onscreen clamping.
 */
data class RailInset(val dockedOnLeft: Boolean, val width: Dp)

val LocalRailInset = compositionLocalOf { RailInset(dockedOnLeft = false, width = 0.dp) }

private val MaxWindowWidth = 320.dp
private val MaxWindowHeight = 480.dp

// A window more than this fraction offscreen is being thrown away, not just moved — closing it
// beats leaving a barely-there sliver nobody can find their way back to.
private const val CLOSE_OFFSCREEN_FRACTION = 0.9f

// How long a position has to hold still before it's treated as "where the user left it" rather
// than "mid-drag" — long enough that an active drag is never fought, short enough that the correct
// onscreen position, or a close, follows quickly once the user lets go.
private const val SETTLE_DEBOUNCE_MS = 300L

/**
 * A floating, draggable, collapsible panel for tool options — Procreate-style, in place of a modal
 * [androidx.compose.material3.AlertDialog]. The header bar is the drag handle; unlike a dialog it
 * never dims or blocks the canvas, so several can be open over the artwork at once, each positioned
 * and collapsed independently.
 *
 * **This is now a thin wrapper over AzNavRail's [AzWindow]** (11.9), not a hand-rolled panel. The
 * ninety lines it replaced were a re-implementation of the same thing, and one of them was a bug:
 * the drag handler was `offset += dragAmount` with no bounds of any kind, so any of the two dozen
 * windows in this app could be dragged clean off the screen and was then unreachable — no way to
 * grab a title bar that isn't on screen, and no way to get the panel back short of closing and
 * reopening it, losing whatever was in it. [AzWindow] clamps the drag so a title bar's worth always
 * stays visible, which is the whole reason to use the library's version rather than keep our own.
 *
 * [AzWindow]'s own clamp only guarantees a sliver of the title bar stays reachable — it happily
 * leaves most of a window hanging off an edge indefinitely, and knows nothing about the rail's
 * footprint at all. Since the library exposes no hook to change that (its drag handling is
 * internal), this wrapper watches the window's own reported position instead: once it settles
 * (see [SETTLE_DEBOUNCE_MS], so a live drag is never fought), a window left mostly [dismissed][onDismiss]
 * beyond [CLOSE_OFFSCREEN_FRACTION] is closed outright; anything short of that is pulled back
 * fully onscreen and clear of [LocalRailInset] by handing [AzWindow] a freshly-seeded
 * [AzWindowState] at the corrected position — the library has no API to move an existing instance,
 * only to construct one at a starting offset, so a correction is a new instance, not a mutation.
 * This only applies to the window's own default state (`state` left null below); a caller-hoisted
 * [AzWindowState] is never replaced out from under it.
 *
 * The signature is deliberately unchanged, so the two dozen call sites did not have to move. What
 * they get for free: the clamp, a fold control that matches every other AzNavRail surface, and an
 * accent that follows the rail's rather than being independently themed here — plus, now, a place
 * in the stack: every call site shares [FloatingWindowStacking] through the same un-keyed defaults,
 * so a window opened after another always lands on top of it and doesn't start at the exact same
 * point on screen. Before this, all two dozen call sites defaulted to the identical fixed position
 * and z-index, so a just-opened window could render invisibly underneath an older one with no hint
 * it had opened at all — Compose breaks a tie between equal z-indices by composition order, which
 * has nothing to do with which window the user actually asked for most recently.
 *
 * [initialOffset] seeds the position once. Hoist an [AzWindowState] via [rememberFloatingWindowState]
 * and pass it as [state] when a window's placement or folded state has to outlive the window itself
 * — a hoisted state's position is the caller's to own, so it is used exactly as given, un-cascaded,
 * and without the onscreen/rail correction described above.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
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
    var internalState by remember {
        val seed = initialOffset + stackSlot.cascadeOffset
        mutableStateOf(AzWindowState(seed.x, seed.y, false))
    }
    // A caller-hoisted state always wins and is never swapped out — see the doc comment above.
    val windowState = state ?: internalState

    val railInset = LocalRailInset.current
    val density = LocalDensity.current
    val containerSize = LocalWindowInfo.current.containerSize

    // Two independent position readings of the same window: `anchor` is where AzWindow would sit
    // with a ZERO offset (captured by sitting earlier than the library's own internal `.offset()`
    // in the modifier chain, so this reading is taken before that offset applies), and `liveRect`
    // is its actual final bounds on screen (captured by the outer Box, which has nothing else
    // inside it). The difference between a desired absolute position and `anchor` is exactly the
    // offset a fresh [AzWindowState] needs to be seeded with to land there.
    var anchor by remember { mutableStateOf<Offset?>(null) }
    var liveRect by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            liveRect = Rect(coordinates.positionInWindow(), coordinates.size.toSize())
        }
    ) {
        AzWindow(
            modifier = modifier
                .onGloballyPositioned { coordinates -> anchor = coordinates.positionInWindow() }
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
        ) {
            Column(modifier = Modifier.padding(12.dp), content = content)
        }
    }

    if (state == null) {
        LaunchedEffect(internalState, railInset, containerSize) {
            val railWidthPx = with(density) { railInset.width.toPx() }
            snapshotFlow { liveRect to anchor }
                .mapNotNull { (rect, a) -> if (rect != null && a != null) rect to a else null }
                .debounce(SETTLE_DEBOUNCE_MS)
                .collectLatest { (rect, a) ->
                    if (visibleFraction(rect, containerSize) <= 1f - CLOSE_OFFSCREEN_FRACTION) {
                        onDismiss()
                        return@collectLatest
                    }
                    val safeTopLeft = clampFullyOnscreen(
                        topLeft = rect.topLeft,
                        size = Offset(rect.width, rect.height),
                        containerSize = containerSize,
                        railInset = railInset,
                        railWidthPx = railWidthPx,
                    )
                    if (safeTopLeft != rect.topLeft) {
                        internalState = AzWindowState(
                            initialOffsetX = safeTopLeft.x - a.x,
                            initialOffsetY = safeTopLeft.y - a.y,
                            initialMinimized = internalState.minimized,
                        )
                    }
                }
        }
    }
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

/** Clamps a top-left position so the whole `size` footprint fits inside the container and clear of the rail. */
private fun clampFullyOnscreen(
    topLeft: Offset,
    size: Offset,
    containerSize: IntSize,
    railInset: RailInset,
    railWidthPx: Float,
): Offset {
    if (containerSize.width <= 0 || containerSize.height <= 0) return topLeft
    val minX = if (railInset.dockedOnLeft) railWidthPx else 0f
    val maxX = (containerSize.width - size.x - (if (railInset.dockedOnLeft) 0f else railWidthPx))
        .coerceAtLeast(minX)
    val minY = 0f
    val maxY = (containerSize.height - size.y).coerceAtLeast(minY)
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
