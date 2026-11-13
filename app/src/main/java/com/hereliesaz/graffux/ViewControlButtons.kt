package com.hereliesaz.graffux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.hereliesaz.graffitixr.common.scene.OrbitStep
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.design.R as DesignR

/**
 * The non-gesture twins of the 3D-group view gestures: Reset view (the double-tap) and four fixed orbit
 * steps (the two-finger drag). Shown only in a project with a 3D group, as ONE 48dp "View" [RailButton]
 * that opens a small popup holding a 4-way orbit pad with Reset view in its centre. Every control is a
 * 48dp focusable button labelled for TalkBack from string resources.
 *
 * The popup is a full-window transparent layer with the pad placed beside the button ([viewPadPosition]),
 * so an outside tap lands on the layer: it dismisses the popup and is consumed, never reaching the canvas
 * (the layer does not rely on window flags). Back dismisses too. An orbit step leaves the pad open so it
 * can be repeated; Reset view closes it. Focus moves into the pad when it opens (and the pad is announced
 * as a pane titled "View controls"), and returns to the View button when it closes. The open state is
 * keyed on [projectId], so a different project starts closed; losing the 3D group removes the button and
 * the pad with it. The orbit steps use the same pivot and pitch clamp as the gesture
 * ([ToolRailActions.onOrbitStep]).
 */
@Suppress("FunctionNaming")
@Composable
internal fun ViewControlButtons(vm: ToolRailActions, projectId: String? = null) {
    var open by remember(projectId) { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    val buttonFocus = remember { FocusRequester() }
    val padFocus = remember { FocusRequester() }
    val close = {
        open = false
        runCatching { buttonFocus.requestFocus() }
        Unit
    }
    Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow().toIntRect() }) {
        RailButton(
            label = stringResource(DesignR.string.rail_view),
            testTag = "rail.view",
            announceSelected = false,
            modifier = Modifier.focusRequester(buttonFocus),
            onClick = { open = true },
        ) { RailGlyph(GraffuxIcons.RotateView) }
    }
    if (open) {
        val gap = with(LocalDensity.current) { RailEdgeGap.roundToPx() }
        Popup(
            popupPositionProvider = WholeWindow,
            onDismissRequest = close,
            properties = PopupProperties(
                focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true,
                clippingEnabled = false, usePlatformDefaultWidth = false,
            ),
        ) {
            Layout(
                content = { ViewPad(vm, padFocus, onReset = close) },
                modifier = Modifier
                    .fillMaxSize()
                    // Outside the pad: dismiss and swallow the tap (the pad's buttons take theirs first).
                    .pointerInput(Unit) { detectTapGestures { close() } },
            ) { measurables, constraints ->
                val pad = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val window = IntSize(constraints.maxWidth, constraints.maxHeight)
                    val at = viewPadPosition(anchor, window, IntSize(pad.width, pad.height), gap)
                    pad.place(at.x, at.y)
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { padFocus.requestFocus() } }
    }
}

/** The orbit pad: up over left / Reset / right over down, so Reset sits where the view is centred. */
@Suppress("FunctionNaming")
@Composable
private fun ViewPad(vm: ToolRailActions, firstFocus: FocusRequester, onReset: () -> Unit) {
    val title = stringResource(DesignR.string.rail_view_controls)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(PadShape)
            .background(RailFill)
            .border(1.dp, RailBorder, PadShape)
            .padding(RailEdgeGap / 2)
            // A tap in the pad's gaps is the pad's, not an outside tap.
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics { paneTitle = title },
    ) {
        OrbitButton(
            OrbitStep.UP, DesignR.string.rail_orbit_up, "rail.view.orbit.up", vm,
            Modifier.focusRequester(firstFocus),
        )
        Row {
            OrbitButton(OrbitStep.LEFT, DesignR.string.rail_orbit_left, "rail.view.orbit.left", vm)
            RailButton(
                label = stringResource(DesignR.string.rail_reset_view),
                testTag = "rail.view.reset",
                announceSelected = false,
                onClick = { vm.resetViewport(); onReset() },
            ) { RailGlyph(GraffuxIcons.ZoomFit) }
            OrbitButton(OrbitStep.RIGHT, DesignR.string.rail_orbit_right, "rail.view.orbit.right", vm)
        }
        OrbitButton(OrbitStep.DOWN, DesignR.string.rail_orbit_down, "rail.view.orbit.down", vm)
    }
}

/** A popup that covers the whole window from its top-left corner. */
private object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ) = IntOffset.Zero
}

/**
 * Where the pad goes: beside the rail [anchor] on whichever side of the window has the anchor's centre
 * (physical left or right, so layout direction does not change it), [gap] away, vertically centred on it,
 * then clamped so the pad stays inside the window (a pad larger than the window pins to the top-left).
 */
internal fun viewPadPosition(anchor: IntRect, window: IntSize, pad: IntSize, gap: Int): IntOffset {
    val x = if (anchor.center.x < window.width / 2) anchor.right + gap else anchor.left - gap - pad.width
    val y = anchor.center.y - pad.height / 2
    return IntOffset(
        x.coerceIn(0, (window.width - pad.width).coerceAtLeast(0)),
        y.coerceIn(0, (window.height - pad.height).coerceAtLeast(0)),
    )
}

private fun androidx.compose.ui.geometry.Rect.toIntRect() =
    IntRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

@Suppress("FunctionNaming")
@Composable
private fun OrbitButton(
    step: OrbitStep, label: Int, tag: String, vm: ToolRailActions, modifier: Modifier = Modifier,
) {
    // One chevron, turned to point the way the view moves (up is the drawing; the others are rotations).
    val degrees = when (step) {
        OrbitStep.UP -> 0f
        OrbitStep.RIGHT -> RIGHT_DEGREES
        OrbitStep.DOWN -> DOWN_DEGREES
        OrbitStep.LEFT -> LEFT_DEGREES
    }
    RailButton(
        label = stringResource(label), testTag = tag, announceSelected = false, modifier = modifier,
        onClick = { vm.onOrbitStep(step) },
    ) {
        Icon(
            painterResource(GraffuxIcons.ChevronUp),
            contentDescription = null,
            tint = RailInk,
            modifier = Modifier.size(RailGlyphSize).rotate(degrees),
        )
    }
}

private const val RIGHT_DEGREES = 90f
private const val DOWN_DEGREES = 180f
private const val LEFT_DEGREES = 270f

private val PadShape = RoundedCornerShape(PAD_CORNER_DP.dp)
private const val PAD_CORNER_DP = 16
