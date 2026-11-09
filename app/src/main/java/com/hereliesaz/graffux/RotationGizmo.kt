@file:Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "LongParameterList", "FunctionNaming", "MaxLineLength")

package com.hereliesaz.graffux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.scene.RotationTriangle
import com.hereliesaz.graffitixr.common.scene.TriangleEdge
import kotlin.math.max

/** The triangle's legs and touch band, in dp. The bands are the 48dp minimum touch size. */
internal val GizmoWidth = 176.dp
internal val GizmoHeight = 120.dp
internal val GizmoBand = 48.dp

/** How much of its full size the docked triangle's legs are drawn at (the 48dp touch bands never shrink). */
internal const val GIZMO_DEFAULT_SCALE = 0.5f

/** The gap kept between the docked triangle's box and the screen edges and system gesture areas. */
internal val GizmoEdgeMargin = 8.dp

/** The spoken name of an edge's axis, "Rotate X". */
internal fun TriangleEdge.label(): String = "Rotate ${axis.name}"

/**
 * Where the docked triangle's box goes: the bottom corner on the brush hand's side ([rightHanded]: bottom-right,
 * else bottom-left), [left], [right] and [bottom] pixels in from the container's edges (the system gesture areas
 * and margin). Pure, so both handednesses can be tested.
 */
internal fun gizmoCorner(
    containerWidth: Int,
    containerHeight: Int,
    boxWidth: Int,
    boxHeight: Int,
    rightHanded: Boolean,
    left: Int,
    right: Int,
    bottom: Int,
): IntOffset {
    val x = if (rightHanded) containerWidth - right - boxWidth else left
    val y = containerHeight - bottom - boxHeight
    return IntOffset(max(0, x), max(0, y))
}

/** The clip that makes only the touch bands take pointer input: their union as an outline. */
private class BandsShape(private val triangle: RotationTriangle, private val inset: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: Density): Outline {
        val path = Path()
        for (poly in triangle.bandOutline()) {
            poly.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(x + inset, y + inset) else path.lineTo(x + inset, y + inset)
            }
            path.close()
        }
        return Outline.Generic(path)
    }
}

/**
 * The right-triangle rotation gizmo (spec/3d-space.md, "Rotation triangle"). The bottom leg turns X, the
 * vertical leg Y, the diagonal Z: drag along an edge and the object turns about its own axis, the full
 * length of the edge being 180 degrees, and there is no end stop, so a full turn is two or three swipes.
 * A drag locks to the edge it began on. Only the 48dp touch bands take pointer input; the interior and the
 * rest of the box do not, so a stroke there still reaches the canvas. Each edge is also an accessibility
 * node with +/- 15 degree actions.
 *
 * [onTurn] gets each increment (degrees, signed) as the finger moves; [onEnd] closes the gesture; [onStep]
 * is one whole accessibility step.
 */
@Composable
internal fun RotationGizmo(
    onTurn: (RotationAxis, Float) -> Unit,
    onEnd: () -> Unit,
    onStep: (RotationAxis, Float) -> Unit,
    modifier: Modifier = Modifier,
    /** The legs' scale (1 = full size), from [gizmoPlacement]; the touch bands keep their size. */
    scale: Float = 1f,
) {
    val density = LocalDensity.current
    val bandPx = with(density) { GizmoBand.toPx() }
    val inset = bandPx / 2f
    val triangle = remember(density.density, scale) {
        RotationTriangle(with(density) { GizmoWidth.toPx() } * scale, with(density) { GizmoHeight.toPx() } * scale, bandPx)
    }
    val turn by rememberUpdatedState(onTurn)
    val end by rememberUpdatedState(onEnd)
    var activeEdge by remember { mutableStateOf<TriangleEdge?>(null) }
    var readout by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val boxW = GizmoWidth * scale + GizmoBand
    val boxH = GizmoHeight * scale + GizmoBand
    Box(modifier.size(boxW, boxH).testTag("rotationGizmo")) {
        // The look: three coloured edges (X cyan, Y pink, Z green, the rail's axis colours); the one in use
        // gets a wide faint halo showing its touch band.
        Canvas(Modifier.matchParentSize()) {
            fun p(x: Float, y: Float) = Offset(x + inset, y + inset)
            val w = triangle.width
            val h = triangle.height
            val corners = mapOf(
                TriangleEdge.BOTTOM to (p(0f, h) to p(w, h)),
                TriangleEdge.SIDE to (p(w, h) to p(w, 0f)),
                TriangleEdge.DIAGONAL to (p(0f, h) to p(w, 0f)),
            )
            for ((edge, ends) in corners) {
                val color = edge.axis.railColor()
                if (edge == activeEdge) {
                    drawLine(color.copy(alpha = 0.25f), ends.first, ends.second, bandPx, StrokeCap.Round)
                }
                drawLine(
                    color, ends.first, ends.second,
                    strokeWidth = if (edge == activeEdge) 7.dp.toPx() else 4.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
        // The axis letters, inside the triangle beside their edges. Not read out: the edges say it.
        val labelOffset = { x: Float, y: Float -> IntOffset((x + inset).toInt(), (y + inset).toInt()) }
        val w = triangle.width
        val h = triangle.height
        val pad = with(density) { 22.dp.toPx() }
        GizmoLetter("X", labelOffset(w / 2f - pad / 3f, h - pad - pad / 2f), TriangleEdge.BOTTOM)
        GizmoLetter("Y", labelOffset(w - pad - pad / 2f, h / 2f - pad / 3f), TriangleEdge.SIDE)
        GizmoLetter("Z", labelOffset(w / 2f - pad, h / 2f - pad / 2f), TriangleEdge.DIAGONAL)
        // The live readout, in the middle of the triangle.
        if (activeEdge != null) {
            Text(
                readout,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                modifier = Modifier
                    .offset { IntOffset((w * 0.38f + inset).toInt(), (h * 0.5f + inset).toInt()) }
                    .testTag("rotationGizmo.readout")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        // One accessibility node per edge: named, with +/- 15 degree actions (what the slider's
        // semantics gave TalkBack). They draw nothing and take no pointer input.
        for (edge in TriangleEdge.entries) {
            val poly = triangle.bandOutline()[edge.ordinal]
            val left = poly.minOf { it.first } + inset
            val top = poly.minOf { it.second } + inset
            val right = poly.maxOf { it.first } + inset
            val bottom = poly.maxOf { it.second } + inset
            val step = RotationTriangle.ACCESSIBILITY_STEP_DEGREES
            Box(
                Modifier
                    .offset { IntOffset(max(0f, left).toInt(), max(0f, top).toInt()) }
                    .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
                    .testTag("rotationGizmo.${edge.axis.name}")
                    .semantics {
                        contentDescription = edge.label()
                        customActions = listOf(
                            CustomAccessibilityAction("${edge.label()} +${step.toInt()}°") {
                                onStep(edge.axis, step); true
                            },
                            CustomAccessibilityAction("${edge.label()} -${step.toInt()}°") {
                                onStep(edge.axis, -step); true
                            },
                        )
                    },
            )
        }
        // The input: a box clipped to the union of the three bands, so nothing else in the gizmo's
        // bounds is hit and canvas strokes through the interior reach the canvas.
        Box(
            Modifier
                .matchParentSize()
                .clip(BandsShape(triangle, inset))
                .testTag("rotationGizmo.input")
                .pointerInput(triangle) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val drag = triangle.beginDrag(down.position.x - inset, down.position.y - inset)
                            ?: return@awaitEachGesture
                        down.consume()
                        activeEdge = drag.edge
                        readout = drag.readout()
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val delta = drag.moveTo(change.position.x - inset, change.position.y - inset)
                                if (delta != 0f) turn(drag.edge.axis, delta)
                                readout = drag.readout()
                                change.consume()
                            }
                        } finally {
                            end()
                            activeEdge = null
                        }
                    }
                },
        )
    }
}

@Composable
private fun GizmoLetter(letter: String, at: IntOffset, edge: TriangleEdge) {
    Text(
        letter,
        style = MaterialTheme.typography.labelMedium,
        color = edge.axis.railColor(),
        modifier = Modifier.offset { at }.clearAndSetSemantics { },
    )
}

/**
 * Puts the triangle in the bottom corner on the brush hand's side, clear of the system gesture areas, the
 * navigation bar and any cutout, whatever the bottom sheet is doing: it does not follow the sheet. It is small
 * and tucked into the corner so it stays off the sheet's handle in the middle. The container takes no pointer
 * input; the triangle's three touch bands do.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun RotationGizmoDock(
    rightHanded: Boolean,
    content: @Composable (scale: Float) -> Unit,
) {
    val density = LocalDensity.current
    val gestures = WindowInsets.systemGestures
    val nav = WindowInsets.navigationBarsIgnoringVisibility
    val cutout = WindowInsets.displayCutout
    val margin = with(density) { GizmoEdgeMargin.roundToPx() }
    Box(
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val ld = layoutDirection
                    val at = gizmoCorner(
                        constraints.maxWidth, constraints.maxHeight, placeable.width, placeable.height, rightHanded,
                        left = max(gestures.getLeft(density, ld), max(nav.getLeft(density, ld), cutout.getLeft(density, ld))) + margin,
                        right = max(gestures.getRight(density, ld), max(nav.getRight(density, ld), cutout.getRight(density, ld))) + margin,
                        bottom = max(gestures.getBottom(density), nav.getBottom(density)) + margin,
                    )
                    placeable.place(at)
                }
            }
            .testTag("rotationGizmo.dock"),
    ) { content(GIZMO_DEFAULT_SCALE) }
}

