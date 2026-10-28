@file:Suppress(
    "MagicNumber", "MaxLineLength", "LongParameterList", "LongMethod", "CyclomaticComplexMethod",
    "FunctionNaming", "MatchingDeclarationName", "LoopWithTooManyJumpStatements",
)

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.unit.dp

/**
 * How a [CurvesAdjustment] graph is drawn. [Default] is the Curves dialog's plain white curve; the
 * photo sheet passes [Photo], the approved mockup's framed graph with a grid, dashed diagonal and a
 * histogram behind the curve.
 */
data class CurvesStyle(
    val frame: Color? = null,
    val frameFill: Color? = null,
    val grid: Color? = null,
    val diagonal: Color? = null,
    val histogram: Color? = null,
    val line: Color = Color.White,
    val lineWidthDp: Float = 2f,
    val pointRadiusDp: Float = 8f,
    val cornerDp: Float = 0f,
) {
    companion object {
        val Default = CurvesStyle()
        val Photo = CurvesStyle(
            frame = Color(0xFF2A2A2E),
            frameFill = Color(0x990E0E0F),
            grid = Color(0xFF1E1E21),
            diagonal = Color(0xFF3A3A3F),
            histogram = Color(0xB33A3A3F),
            line = Color(0xFFEDEDED),
            lineWidthDp = 2f,
            pointRadiusDp = 6f,
            cornerDp = 6f,
        )
    }
}

/**
 * A tone-curve editor. [points] are normalized (0..1), x across and y **down** (0 at the top), the
 * first and last being the ends. Drag a point near the finger to move it between its neighbours.
 *
 * With [editablePoints], a touch on the line away from any point adds a point there (and drags
 * it), and a point other than the ends dragged off the graph (above or below it) is removed when
 * released. [curve], when given, is what is drawn as the line (256 outputs, y up, as the curve is
 * applied); otherwise the points are joined by the dialog's own smooth segments. [histogram]
 * (bins of any count, 0..1 heights) is filled behind it. [onDragStart] runs once per gesture,
 * before its first change.
 */
@Composable
fun CurvesAdjustment(
    points: List<Offset>,
    onPointsChanged: (List<Offset>) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
    editablePoints: Boolean = false,
    curve: FloatArray? = null,
    histogram: FloatArray? = null,
    style: CurvesStyle = CurvesStyle.Default,
) {
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }
    val currentPoints by rememberUpdatedState(points)
    val currentCurve by rememberUpdatedState(curve)
    val latestChange by rememberUpdatedState(onPointsChanged)
    val latestEnd by rememberUpdatedState(onDragEnd)
    val latestStart by rememberUpdatedState(onDragStart)

    val gestures = if (!editablePoints) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { startOffset ->
                    val snap = currentPoints
                    val scaledPoints = snap.map { Offset(it.x * size.width, it.y * size.height) }
                    val closestPoint = scaledPoints.minByOrNull { (it - startOffset).getDistance() }
                    val closestPointIndex = scaledPoints.indexOf(closestPoint)
                    if (closestPoint != null && (closestPoint - startOffset).getDistance() < 20.dp.toPx()) {
                        selectedPointIndex = closestPointIndex
                    }
                },
                onDrag = { _, dragAmount ->
                    selectedPointIndex?.let { index ->
                        val snap = currentPoints
                        val newPoints = snap.toMutableList()
                        val newPoint = snap[index] + Offset(dragAmount.x / size.width, dragAmount.y / size.height)
                        val lowerX = if (index > 0) snap[index - 1].x else 0f
                        val upperX = if (index < snap.lastIndex) snap[index + 1].x else 1f
                        newPoints[index] = newPoint.copy(
                            x = newPoint.x.coerceIn(lowerX, upperX),
                            y = newPoint.y.coerceIn(0f, 1f)
                        )
                        latestChange(newPoints)
                    }
                },
                onDragEnd = {
                    selectedPointIndex = null
                    latestEnd()
                }
            )
        }
    } else {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val reach = 20.dp.toPx()
                val start = currentPoints
                var pts = start
                var index = pts.indices.minByOrNull { (Offset(pts[it].x * w, pts[it].y * h) - down.position).getDistance() }
                    ?.takeIf { (Offset(pts[it].x * w, pts[it].y * h) - down.position).getDistance() < reach }
                if (index == null) {
                    // On the line: a new point, where the line is.
                    val x = (down.position.x / w).coerceIn(0f, 1f)
                    val lineY = currentCurve?.let { 1f - it[(x * 255f).toInt().coerceIn(0, 255)] }
                        ?: (down.position.y / h)
                    if (kotlin.math.abs(lineY * h - down.position.y) > reach * 1.5f) return@awaitEachGesture
                    val at = pts.indexOfFirst { it.x > x }.takeIf { it > 0 } ?: return@awaitEachGesture
                    pts = pts.toMutableList().apply { add(at, Offset(x, lineY)) }
                    index = at
                    latestStart()
                    latestChange(pts)
                } else {
                    latestStart()
                }
                selectedPointIndex = index
                var removed = false
                var pos = Offset(pts[index].x * w, pts[index].y * h)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    pos += change.positionChangeIgnoreConsumed()
                    val isEnd = index == 0 || index == pts.lastIndex
                    removed = !isEnd && (pos.y < -reach * 1.5f || pos.y > h + reach * 1.5f)
                    val lowerX = if (index > 0) pts[index - 1].x else 0f
                    val upperX = if (index < pts.lastIndex) pts[index + 1].x else 1f
                    val moved = Offset((pos.x / w).coerceIn(lowerX, upperX), (pos.y / h).coerceIn(0f, 1f))
                    pts = pts.toMutableList().also { it[index] = moved }
                    latestChange(if (removed) pts.filterIndexed { i, _ -> i != index } else pts)
                }
                selectedPointIndex = null
                latestEnd()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize().then(gestures)) {
            drawGraph(style, histogram)
            val scaledPoints = points.map { Offset(it.x * size.width, it.y * size.height) }
            val path = Path()
            if (curve != null) {
                for (i in 0 until 256) {
                    val x = i / 255f * size.width
                    val y = (1f - curve[i]) * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            } else {
                path.moveTo(scaledPoints.first().x, scaledPoints.first().y)
                for (i in 0 until scaledPoints.size - 1) {
                    val controlPoint1 = Offset(
                        (scaledPoints[i].x + scaledPoints[i + 1].x) / 2,
                        scaledPoints[i].y
                    )
                    val controlPoint2 = Offset(
                        (scaledPoints[i].x + scaledPoints[i + 1].x) / 2,
                        scaledPoints[i + 1].y
                    )
                    path.cubicTo(
                        controlPoint1.x, controlPoint1.y,
                        controlPoint2.x, controlPoint2.y,
                        scaledPoints[i + 1].x, scaledPoints[i + 1].y
                    )
                }
            }

            drawPath(
                path = path,
                color = style.line,
                style = Stroke(width = style.lineWidthDp.dp.toPx())
            )

            // The ends of a photo curve are its corners, not handles worth drawing.
            val shown = if (editablePoints) scaledPoints.drop(1).dropLast(1) else scaledPoints
            shown.forEach { point ->
                drawCircle(
                    color = style.line,
                    radius = style.pointRadiusDp.dp.toPx(),
                    center = point
                )
            }
        }
    }
}

/** The frame, grid (quarters), histogram and dashed diagonal that [CurvesStyle] asks for. */
private fun DrawScope.drawGraph(style: CurvesStyle, histogram: FloatArray?) {
    val corner = CornerRadius(style.cornerDp.dp.toPx())
    style.frameFill?.let { drawRoundRect(it, cornerRadius = corner) }
    style.grid?.let { grid ->
        for (q in 1..3) {
            val x = size.width * q / 4f
            val y = size.height * q / 4f
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
    }
    val bins = histogram
    if (style.histogram != null && bins != null && bins.isNotEmpty()) {
        val path = Path().apply {
            moveTo(0f, size.height)
            bins.forEachIndexed { i, v ->
                lineTo(i / (bins.size - 1f).coerceAtLeast(1f) * size.width, size.height * (1f - v.coerceIn(0f, 1f)))
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, style.histogram)
    }
    style.diagonal?.let {
        drawLine(
            it, Offset(0f, size.height), Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
        )
    }
    style.frame?.let { drawRoundRect(it, cornerRadius = corner, style = Stroke(1.dp.toPx())) }
}
