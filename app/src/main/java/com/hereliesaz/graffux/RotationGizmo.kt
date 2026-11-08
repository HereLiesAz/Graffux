@file:Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "LongParameterList", "FunctionNaming", "MaxLineLength")

package com.hereliesaz.graffux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.scene.RotationTriangle
import com.hereliesaz.graffitixr.common.scene.TriangleEdge
import kotlin.math.max
import kotlin.math.min

/** The triangle's legs and touch band, in dp. The bands are the 48dp minimum touch size. */
internal val GizmoWidth = 176.dp
internal val GizmoHeight = 120.dp
internal val GizmoBand = 48.dp

/** The gap between the triangle's bottom edge and the top of the sheet it rides on. */
internal val GizmoSheetGap = 8.dp

/** The spoken name of an edge's axis, "Rotate X". */
internal fun TriangleEdge.label(): String = "Rotate ${axis.name}"

/**
 * Where the triangle's bottom edge sits (spec/3d-space.md, "Rotation triangle"): on the top edge of the
 * bottom sheet ([sheetTop], in the container's pixels, NaN when no sheet is shown) less [gapPx], but
 * never lower than the bottom safe area ([safeBottomPx] up from the container's bottom).
 */
internal fun gizmoBottomY(containerHeight: Float, safeBottomPx: Float, sheetTop: Float, gapPx: Float): Float {
    val floor = containerHeight - safeBottomPx
    return if (sheetTop.isNaN()) floor else min(floor, sheetTop - gapPx)
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
) {
    val density = LocalDensity.current
    val bandPx = with(density) { GizmoBand.toPx() }
    val inset = bandPx / 2f
    val triangle = remember(density.density) {
        RotationTriangle(with(density) { GizmoWidth.toPx() }, with(density) { GizmoHeight.toPx() }, bandPx)
    }
    val turn by rememberUpdatedState(onTurn)
    val end by rememberUpdatedState(onEnd)
    var activeEdge by remember { mutableStateOf<TriangleEdge?>(null) }
    var readout by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val boxW = GizmoWidth + GizmoBand
    val boxH = GizmoHeight + GizmoBand
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
 * Puts the triangle in the right corner, riding the top edge of the bottom sheet (it moves with the sheet as
 * it opens, closes and resizes), or in the bottom safe area when no sheet is shown. [sheetTop] is read at
 * layout time only: the sheet's top in root pixels, NaN with no sheet. [sideClearance] keeps it in from the
 * edge, clear of the rails. The container takes no pointer input.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun RotationGizmoDock(
    sheetTop: () -> Float,
    sideClearance: Dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val safeBottom = WindowInsets.navigationBarsIgnoringVisibility.getBottom(density) +
        with(density) { CarouselBottomInset.toPx() }
    var originY by remember { mutableStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { originY = it.positionInRoot().y }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val x = constraints.maxWidth - placeable.width - with(density) { sideClearance.roundToPx() }
                    val bottom = gizmoBottomY(
                        constraints.maxHeight.toFloat(), safeBottom, sheetTop() - originY, with(density) { GizmoSheetGap.toPx() },
                    )
                    placeable.place(max(0, x), (bottom - placeable.height).toInt())
                }
            }
            .testTag("rotationGizmo.dock"),
    ) { content() }
}
