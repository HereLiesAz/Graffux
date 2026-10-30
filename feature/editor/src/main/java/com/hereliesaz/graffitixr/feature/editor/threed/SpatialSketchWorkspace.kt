@file:Suppress(
    "LongParameterList", "LongMethod", "CyclomaticComplexMethod", "FunctionNaming",
    "LoopWithTooManyJumpStatements", "MagicNumber",
)
// Compose entrypoints and gesture arbitration need stable, explicit callbacks and numeric bounds.

package com.hereliesaz.graffitixr.feature.editor.threed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.opengl.GLSurfaceView
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.mesh.OrbitCamera
import com.hereliesaz.graffitixr.common.mesh.SpatialGuide
import com.hereliesaz.graffitixr.common.mesh.SpatialGuideKind
import com.hereliesaz.graffitixr.common.mesh.SpatialGuideSurface
import com.hereliesaz.graffitixr.common.mesh.SpatialPoint
import com.hereliesaz.graffitixr.common.mesh.SpatialSketchGeometry
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import java.util.UUID

private enum class SketchTool { DRAW, ORBIT, ERASE }

/** The currently edited guide parameter; other dimensions are kept intact during a step. */
private enum class GuideParameter { DEPTH, X, Y, Z, RADIUS, HEIGHT, YAW, PITCH }

/** A constrained, reversible step of one guide parameter, leaving committed strokes untouched. */
private fun adjustGuide(guide: SpatialGuide, parameter: GuideParameter, direction: Float): SpatialGuide {
    val step = if (parameter == GuideParameter.YAW || parameter == GuideParameter.PITCH) {
        (kotlin.math.PI / 12.0).toFloat() * direction
    } else {
        0.1f * direction
    }
    return when (parameter) {
        GuideParameter.DEPTH -> guide.copy(depth = (guide.depth + step).coerceIn(-4f, 4f))
        GuideParameter.X -> guide.copy(center = guide.center.copy(x = (guide.center.x + step).coerceIn(-4f, 4f)))
        GuideParameter.Y -> guide.copy(center = guide.center.copy(y = (guide.center.y + step).coerceIn(-4f, 4f)))
        GuideParameter.Z -> guide.copy(center = guide.center.copy(z = (guide.center.z + step).coerceIn(-4f, 4f)))
        GuideParameter.RADIUS -> guide.copy(radius = (guide.radius + step).coerceIn(0.15f, 4f))
        GuideParameter.HEIGHT -> guide.copy(height = (guide.height + step).coerceIn(0.3f, 8f))
        GuideParameter.YAW -> guide.copy(yaw = guide.yaw + step)
        GuideParameter.PITCH -> guide.copy(pitch = (guide.pitch + step).coerceIn(-1.55f, 1.55f))
    }
}

/**
 * Full-screen 3D drawing: each gesture is unprojected onto a camera-facing guide at an editable
 * depth. After commit the points are world coordinates, not screen pixels, so orbiting reveals
 * real parallax. A stylus or finger paints; two fingers always navigate.
 */
@Composable
fun SpatialSketchWorkspace(
    strokes: List<SpatialStroke>,
    guide: SpatialGuide,
    onGuideChange: (SpatialGuide) -> Unit,
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
    var parameter by remember(guide.kind) {
        mutableStateOf(if (guide.kind == SpatialGuideKind.VIEW_PLANE) GuideParameter.DEPTH else GuideParameter.RADIUS)
    }
    var cameraReset by remember { mutableIntStateOf(0) }

    Box(modifier.fillMaxSize().background(Color(0xFF202025))) {
        SketchViewport(
            strokes, guide, tool, cameraReset, color.toArgb(), brushRadiusPx,
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
            // Switching guides changes only future samples; already painted XYZ never follows
            // the guide. The horizontally scrollable controls remain usable on narrow screens.
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                for (kind in SpatialGuideKind.entries) {
                    TextButton(onClick = { onGuideChange(guide.copy(kind = kind)) }) {
                        val label = when (kind) {
                            SpatialGuideKind.VIEW_PLANE -> "View"
                            SpatialGuideKind.PLANE -> "Plane"
                            SpatialGuideKind.CYLINDER -> "Cylinder"
                            SpatialGuideKind.SPHERE -> "Sphere"
                        }
                        Text(if (kind == guide.kind) "● $label" else label)
                    }
                }
                TextButton(onClick = { onGuideChange(guide.copy(visible = !guide.visible)) }) {
                    Text(if (guide.visible) "Hide guide" else "Show guide")
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                val options = if (guide.kind == SpatialGuideKind.VIEW_PLANE) {
                    listOf(GuideParameter.DEPTH, GuideParameter.X, GuideParameter.Y,
                        GuideParameter.Z, GuideParameter.RADIUS)
                } else {
                    listOf(GuideParameter.X, GuideParameter.Y, GuideParameter.Z, GuideParameter.RADIUS) +
                        (if (guide.kind == SpatialGuideKind.CYLINDER) listOf(GuideParameter.HEIGHT) else emptyList()) +
                        (if (guide.kind != SpatialGuideKind.SPHERE) {
                            listOf(GuideParameter.YAW, GuideParameter.PITCH)
                        } else emptyList())
                }
                for (option in options) {
                    TextButton(onClick = { parameter = option }) {
                        Text(if (parameter == option) "● ${option.name.lowercase()}" else option.name.lowercase())
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val selected = when (parameter) {
                    GuideParameter.DEPTH -> guide.depth
                    GuideParameter.X -> guide.center.x
                    GuideParameter.Y -> guide.center.y
                    GuideParameter.Z -> guide.center.z
                    GuideParameter.RADIUS -> guide.radius
                    GuideParameter.HEIGHT -> guide.height
                    GuideParameter.YAW -> Math.toDegrees(guide.yaw.toDouble()).toFloat()
                    GuideParameter.PITCH -> Math.toDegrees(guide.pitch.toDouble()).toFloat()
                }
                TextButton(onClick = { onGuideChange(adjustGuide(guide, parameter, -1f)) }) { Text("−") }
                Text("${parameter.name.lowercase()}: ${"%.2f".format(selected)}", color = Color.LightGray)
                TextButton(onClick = { onGuideChange(adjustGuide(guide, parameter, 1f)) }) { Text("+") }
                TextButton(onClick = { onGuideChange(SpatialGuide(kind = guide.kind)) }) { Text("Reset guide") }
                TextButton(onClick = { cameraReset++ }) { Text("Reset view") }
            }
        }
    }
}

@Composable
private fun SketchViewport(
    strokes: List<SpatialStroke>,
    guide: SpatialGuide,
    tool: SketchTool,
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

    // Scene ink is depth-tested in GLES; Compose owns only the translucent guide and gestures.
    val renderer = remember { SpatialStrokeRenderer() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val surface = remember(context) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }
    // A preview uses the same triangles as committed geometry, so drawing does not jump when
    // the pen lifts. Only geometry changes trigger tessellation; camera movement updates MVP.
    var viewportHeight by remember { mutableIntStateOf(1) }
    val visibleStrokes = remember(strokes, draft, colorArgb, brushRadiusPx, camera, viewportHeight) {
        if (draft.isEmpty()) strokes else strokes + SpatialStroke(
            "live-preview", draft, colorArgb,
            SpatialSketchGeometry.widthForBrush(camera, brushRadiusPx, viewportHeight.toFloat()),
        )
    }
    LaunchedEffect(camera, visibleStrokes) {
        renderer.camera = camera
        renderer.setStrokes(visibleStrokes)
        surface.requestRender()
    }
    DisposableEffect(lifecycleOwner, surface) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surface.onResume()
                Lifecycle.Event.ON_PAUSE -> surface.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            surface.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            surface.onPause()
        }
    }

    Box(modifier.onSizeChanged { viewportHeight = it.height.coerceAtLeast(1) }) {
        AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize().pointerInput(strokes, guide, tool, colorArgb, brushRadiusPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val worldWidth = SpatialSketchGeometry.widthForBrush(camera, brushRadiusPx, h)
            fun sample(pos: Offset, pressure: Float): SpatialPoint? =
                SpatialGuideSurface.intersect(guide, camera, pos.x, pos.y, w, h, pressure)
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
                            val hit = sample(change.position, change.pressure)
                            if (hit == null) {
                                // Bounded surfaces have holes in screen space. Never bridge
                                // across a miss with an invented straight 3D segment.
                                if (points.isNotEmpty()) commit(points)
                                points = mutableListOf()
                            } else {
                                points.add(hit)
                            }
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
        for ((a, b) in SpatialGuideSurface.wireframe(guide, camera)) {
            val one = SpatialSketchGeometry.project(camera, a, size.width, size.height)
            val two = SpatialSketchGeometry.project(camera, b, size.width, size.height)
            if (one != null && two != null) {
                drawLine(Color.White.copy(alpha = 0.10f), Offset(one.first, one.second),
                    Offset(two.first, two.second), strokeWidth = 1f)
            }
        }
    }
    }
}
