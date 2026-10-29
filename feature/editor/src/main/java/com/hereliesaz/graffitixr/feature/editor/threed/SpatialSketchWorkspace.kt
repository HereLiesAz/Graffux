package com.hereliesaz.graffitixr.feature.editor.threed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.mesh.OrbitCamera
import com.hereliesaz.graffitixr.common.mesh.SpatialPoint
import com.hereliesaz.graffitixr.common.mesh.SpatialSketchGeometry
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import java.util.UUID

private enum class SketchTool { DRAW, ORBIT, ERASE }

/**
 * Full-screen 3D drawing: each gesture is unprojected onto a camera-facing guide at an editable
 * depth. After commit the points are world coordinates, not screen pixels, so orbiting reveals
 * real parallax. A stylus or finger paints; two fingers always navigate.
 */
@Composable
fun SpatialSketchWorkspace(
    strokes: List<SpatialStroke>,
    color: Color,
    brushRadiusPx: Float,
    onStroke: (SpatialStroke) -> Unit,
    onErase: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tool by remember { mutableStateOf(SketchTool.DRAW) }
    var depth by remember { mutableFloatStateOf(0f) }
    var cameraReset by remember { mutableIntStateOf(0) }

    Box(modifier.fillMaxSize().background(Color(0xFF202025))) {
        SketchViewport(
            strokes, tool, depth, cameraReset, color.toArgb(), brushRadiusPx,
            onStroke, onErase, Modifier.fillMaxSize(),
        )
        Row(
            Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing)
                .background(Color(0xDD101015)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("‹ 2D") }
            Text("Spatial Sketch · ${strokes.size}", color = Color.White)
            TextButton(onClick = onExport) { Text("Export glTF") }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                .background(Color(0xE8101015)).padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { tool = SketchTool.DRAW }) {
                    Text(if (tool == SketchTool.DRAW) "● Draw" else "Draw")
                }
                TextButton(onClick = { tool = SketchTool.ORBIT }) {
                    Text(if (tool == SketchTool.ORBIT) "● Orbit" else "Orbit")
                }
                TextButton(onClick = { tool = SketchTool.ERASE }) {
                    Text(if (tool == SketchTool.ERASE) "● Erase" else "Erase")
                }
                TextButton(onClick = onUndo) { Text("Undo") }
                TextButton(onClick = onRedo) { Text("Redo") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { depth = (depth - 0.25f).coerceAtLeast(-2f) }) { Text("Depth −") }
                Text("${"%.2f".format(depth)}", color = Color.LightGray)
                TextButton(onClick = { depth = (depth + 0.25f).coerceAtMost(2f) }) { Text("Depth +") }
                TextButton(onClick = { cameraReset++ }) { Text("Reset view") }
            }
        }
    }
}

@Composable
private fun SketchViewport(
    strokes: List<SpatialStroke>,
    tool: SketchTool,
    depth: Float,
    cameraReset: Int,
    colorArgb: Int,
    brushRadiusPx: Float,
    onStroke: (SpatialStroke) -> Unit,
    onErase: (String) -> Unit,
    modifier: Modifier,
) {
    var camera by remember { mutableStateOf(OrbitCamera(pitch = 0f)) }
    var draft by remember { mutableStateOf<List<SpatialPoint>>(emptyList()) }
    LaunchedEffect(cameraReset) { camera = OrbitCamera(pitch = 0f) }

    Canvas(modifier.pointerInput(strokes, tool, depth, colorArgb, brushRadiusPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val worldWidth = SpatialSketchGeometry.widthForBrush(camera, brushRadiusPx, h)
            fun sample(pos: Offset, pressure: Float): SpatialPoint? =
                SpatialSketchGeometry.onViewPlane(camera, pos.x, pos.y, w, h, depth, pressure)
            fun commit(points: List<SpatialPoint>) {
                if (points.isNotEmpty()) {
                    onStroke(SpatialStroke(UUID.randomUUID().toString(), points.toList(), colorArgb, worldWidth))
                }
            }
            var points = mutableListOf<SpatialPoint>()
            var navigation = false
            if (tool == SketchTool.DRAW) {
                sample(down.position, down.pressure)?.let(points::add)
                draft = points.toList()
            } else if (tool == SketchTool.ERASE) {
                SpatialSketchGeometry.nearestStrokeId(strokes, camera, down.position.x, down.position.y, w, h)
                    ?.let(onErase)
            }
            var pressedCount: Int
            do {
                val event = awaitPointerEvent()
                val active = event.changes.filter { it.pressed }
                pressedCount = active.size
                if (pressedCount >= 2) {
                    if (!navigation) commit(points)
                    points = mutableListOf()
                    draft = emptyList()
                    navigation = true
                    camera = camera.dolly(event.calculateZoom()).let {
                        val pan = event.calculatePan()
                        it.pan(pan.x, pan.y)
                    }
                } else if (pressedCount == 1) {
                    val change = active.first()
                    when (tool) {
                        SketchTool.DRAW -> if (!navigation) {
                            sample(change.position, change.pressure)?.let(points::add)
                            draft = points.toList()
                        }
                        SketchTool.ORBIT -> {
                            val pan = event.calculatePan()
                            camera = camera.orbit(-pan.x * 0.008f, pan.y * 0.008f)
                        }
                        SketchTool.ERASE -> {
                            SpatialSketchGeometry.nearestStrokeId(
                                strokes, camera, change.position.x, change.position.y, w, h,
                            )?.let(onErase)
                        }
                    }
                }
                event.changes.forEach { it.consume() }
            } while (pressedCount > 0)
            if (!navigation) commit(points)
            draft = emptyList()
        }
    }) {
        // The translucent guide is regenerated for the current view; finished ink never moves.
        for ((a, b) in SpatialSketchGeometry.guide(camera, depth)) {
            val one = SpatialSketchGeometry.project(camera, a, size.width, size.height)
            val two = SpatialSketchGeometry.project(camera, b, size.width, size.height)
            if (one != null && two != null) {
                drawLine(Color.White.copy(alpha = 0.10f), Offset(one.first, one.second),
                    Offset(two.first, two.second), strokeWidth = 1f)
            }
        }
        val eye = camera.eye()
        val ordered = strokes.sortedByDescending { s ->
            s.points.firstOrNull()?.let { p ->
                val x = p.x - eye[0]; val y = p.y - eye[1]; val z = p.z - eye[2]
                x * x + y * y + z * z
            } ?: 0f
        }
        ordered.forEach { renderStroke(it, camera) }
        if (draft.isNotEmpty()) {
            renderStroke(SpatialStroke("preview", draft, colorArgb,
                SpatialSketchGeometry.widthForBrush(camera, brushRadiusPx, size.height)), camera)
        }
    }
}

private fun DrawScope.renderStroke(stroke: SpatialStroke, camera: OrbitCamera) {
    val tint = Color(stroke.colorArgb)
    for (i in stroke.points.indices) {
        val point = stroke.points[i]
        val one = SpatialSketchGeometry.project(camera, point, size.width, size.height) ?: continue
        val width = (SpatialSketchGeometry.widthPixels(camera, point, stroke.width, size.height) *
            point.pressure.coerceIn(0.05f, 1f)).coerceAtLeast(1f)
        val start = Offset(one.first, one.second)
        if (i == 0) drawCircle(tint, radius = width / 2f, center = start)
        val next = stroke.points.getOrNull(i + 1) ?: continue
        val two = SpatialSketchGeometry.project(camera, next, size.width, size.height) ?: continue
        drawLine(tint, start, Offset(two.first, two.second), strokeWidth = width, cap = StrokeCap.Round)
    }
}
