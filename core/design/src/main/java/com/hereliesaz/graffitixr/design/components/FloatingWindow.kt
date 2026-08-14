// FILE: core/design/src/main/java/com/hereliesaz/graffitixr/design/components/FloatingWindow.kt
package com.hereliesaz.graffitixr.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hereliesaz.aznavrail.AzWindow
import com.hereliesaz.aznavrail.AzWindowState

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
 * — a hoisted state's position is the caller's to own, so it is used exactly as given, un-cascaded.
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
    val windowState = state ?: rememberFloatingWindowState(initialOffset + stackSlot.cascadeOffset)
    AzWindow(
        modifier = modifier.zIndex(stackSlot.zIndex).widthIn(min = 220.dp, max = 320.dp),
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
