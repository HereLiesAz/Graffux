package com.hereliesaz.graffux

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.hereliesaz.graffitixr.common.scene.OrbitStep
import com.hereliesaz.graffitixr.design.GraffuxIcons
import com.hereliesaz.graffitixr.design.R as DesignR

/**
 * The non-gesture twins of the 3D-group view gestures: Reset view (the double-tap) and four fixed orbit
 * steps (the two-finger drag). Shown only in a project with a 3D group. Each is a 48dp [RailButton]
 * (focusable, clickable by keyboard, labelled for TalkBack from string resources). The orbit steps use
 * the same pivot and pitch clamp as the gesture ([ToolRailActions.onOrbitStep]).
 */
@Suppress("FunctionNaming")
@Composable
internal fun ViewControlButtons(vm: ToolRailActions) {
    RailButton(
        label = stringResource(DesignR.string.rail_reset_view),
        testTag = "rail.view.reset",
        announceSelected = false,
        onClick = { vm.resetViewport() },
    ) { RailGlyph(GraffuxIcons.ZoomFit) }
    OrbitButton(OrbitStep.LEFT, DesignR.string.rail_orbit_left, "rail.view.orbit.left", vm)
    OrbitButton(OrbitStep.RIGHT, DesignR.string.rail_orbit_right, "rail.view.orbit.right", vm)
    OrbitButton(OrbitStep.UP, DesignR.string.rail_orbit_up, "rail.view.orbit.up", vm)
    OrbitButton(OrbitStep.DOWN, DesignR.string.rail_orbit_down, "rail.view.orbit.down", vm)
}

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
