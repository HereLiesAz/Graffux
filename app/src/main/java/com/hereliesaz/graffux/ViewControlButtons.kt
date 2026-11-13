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
 * 48dp focusable button labelled for TalkBack from string resources. The popup closes on an outside tap
 * or Back; an orbit step leaves it open so it can be repeated, and Reset view closes it. The orbit steps
 * use the same pivot and pitch clamp as the gesture ([ToolRailActions.onOrbitStep]).
 */
@Suppress("FunctionNaming")
@Composable
internal fun ViewControlButtons(vm: ToolRailActions) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow().toIntRect() }) {
        RailButton(
            label = stringResource(DesignR.string.rail_view),
            testTag = "rail.view",
            announceSelected = false,
            onClick = { open = true },
        ) { RailGlyph(GraffuxIcons.RotateView) }
    }
    if (open) {
        Popup(
            popupPositionProvider = SideOfAnchor(anchor, with(LocalDensity.current) { RailEdgeGap.roundToPx() }),
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            ViewPad(vm, onReset = { open = false })
        }
    }
}

/** The orbit pad: up over left / Reset / right over down, so Reset sits where the view is centred. */
@Suppress("FunctionNaming")
@Composable
private fun ViewPad(vm: ToolRailActions, onReset: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(PadShape)
            .background(RailFill)
            .border(1.dp, RailBorder, PadShape)
            .padding(RailEdgeGap / 2),
    ) {
        OrbitButton(OrbitStep.UP, DesignR.string.rail_orbit_up, "rail.view.orbit.up", vm)
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

/** Beside the rail button, on whichever side has room, vertically centred on it and kept inside the window. */
private class SideOfAnchor(private val anchor: IntRect, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val right = anchor.right + gap
        val left = anchor.left - gap - popupContentSize.width
        val x = if (anchor.center.x < windowSize.width / 2) right else left
        val y = anchor.center.y - popupContentSize.height / 2
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

private fun androidx.compose.ui.geometry.Rect.toIntRect() =
    IntRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

@Suppress("FunctionNaming")
@Composable
private fun OrbitButton(step: OrbitStep, label: Int, tag: String, vm: ToolRailActions) {
    // One chevron, turned to point the way the view moves (up is the drawing; the others are rotations).
    val degrees = when (step) {
        OrbitStep.UP -> 0f
        OrbitStep.RIGHT -> RIGHT_DEGREES
        OrbitStep.DOWN -> DOWN_DEGREES
        OrbitStep.LEFT -> LEFT_DEGREES
    }
    RailButton(
        label = stringResource(label), testTag = tag, announceSelected = false,
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
