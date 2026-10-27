@file:Suppress("MagicNumber", "MaxLineLength", "LongParameterList", "CyclomaticComplexMethod", "MatchingDeclarationName", "FunctionNaming", "LongMethod", "TooManyFunctions")

package com.hereliesaz.graffux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import com.hereliesaz.graffitixr.feature.editor.StrokeGate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/**
 * A finger on the canvas. The chrome (both rails, every menu, the floating toolboxes, the brush
 * sheet) gets out of the way the instant one lands and comes back [DRAWING_UI_RETURN_DELAY_MS]
 * after the last one lifts.
 *
 * Two signals say a finger is on the canvas: a stroke ([StrokeGate.strokeActive], set by every
 * drawing surface on its first point), and a touch nothing on the canvas claimed (a tap with no
 * tool, a pinch) — [observeCanvasTouch] sees those. A touch a panel's own control claims (a slider
 * in the Adjust panel) is neither, so adjusting something does not hide the rails it was opened from.
 */
@Stable
internal class CanvasTouch {
    /** True while an unclaimed finger is down on the canvas. */
    var down by mutableStateOf(false)
        private set

    /** Bumped on every touch-down; [rememberChromeVisibility] closes menus on each bump. */
    var touches by mutableStateOf(0)
        private set

    fun press() {
        down = true
        touches++
    }

    fun release() {
        down = false
    }
}

/**
 * Watches the canvas for [CanvasTouch]. Goes on the canvas root's outermost modifier: in the Main
 * pass that runs after every surface inside it, so a down it sees still unconsumed is one no control
 * took.
 */
internal fun Modifier.observeCanvasTouch(touch: CanvasTouch): Modifier = pointerInput(touch) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            when {
                event.changes.none { it.pressed } -> touch.release()
                !touch.down && event.changes.any { it.changedToDownIgnoreConsumed() && !it.isConsumed } ->
                    touch.press()
            }
        }
    }
}

/**
 * Whether the chrome is hidden, read lazily so only the composables that hide recompose. Hidden at
 * once on a canvas touch or a stroke — no animation, no frame of delay beyond the touch itself — and
 * for [DRAWING_UI_RETURN_DELAY_MS] after.
 */
@Stable
internal class ChromeVisibility(
    private val touch: CanvasTouch,
    private val gate: StrokeGate,
    private val held: State<Boolean>,
) {
    val hidden: Boolean get() = touch.down || gate.strokeActive || held.value
}

/**
 * [ChromeVisibility] for [touch] and [gate]. [onTouch] runs on every canvas touch-down and every
 * stroke start: it is where menus close, the sheet shuts and expanded cards collapse — those stay
 * closed when the chrome comes back.
 */
@Composable
internal fun rememberChromeVisibility(touch: CanvasTouch, gate: StrokeGate, onTouch: () -> Unit): ChromeVisibility {
    val held = rememberDrawingUiHidden { gate.strokeActive || touch.down }
    val latestOnTouch by rememberUpdatedState(onTouch)
    LaunchedEffect(touch) {
        snapshotFlow { touch.touches }.drop(1).collect { latestOnTouch() }
    }
    LaunchedEffect(gate) {
        snapshotFlow { gate.strokeActive }.filter { it }.collect { latestOnTouch() }
    }
    return remember(touch, gate, held) { ChromeVisibility(touch, gate, held) }
}

/** Composes [content] only while the chrome is shown. Gone, not faded: nothing to touch by accident. */
@Suppress("FunctionNaming")
@Composable
internal fun ShownWhenNotDrawing(visibility: ChromeVisibility, content: @Composable () -> Unit) {
    if (!visibility.hidden) content()
}
