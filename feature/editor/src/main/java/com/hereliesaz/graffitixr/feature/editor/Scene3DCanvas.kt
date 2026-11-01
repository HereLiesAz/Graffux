@file:Suppress(
    "MagicNumber", "FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "LongParameterList",
    "TooManyFunctions", "MaxLineLength", "LoopWithTooManyJumpStatements", "ComplexCondition")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.mesh.SpatialGuide
import com.hereliesaz.graffitixr.common.mesh.SpatialGuideKind
import com.hereliesaz.graffitixr.common.mesh.SpatialGuideSurface
import com.hereliesaz.graffitixr.common.mesh.SpatialPoint
import com.hereliesaz.graffitixr.common.mesh.SpatialSketchGeometry
import com.hereliesaz.graffitixr.common.mesh.SpatialStroke
import com.hereliesaz.graffitixr.common.mesh.SpatialView
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.scene.Affine3
import com.hereliesaz.graffitixr.common.scene.CameraFrame
import com.hereliesaz.graffitixr.common.scene.Scene3DTool
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.scene.planeChain
import com.hereliesaz.graffitixr.feature.editor.threed.SpatialInkLayer
import java.util.UUID

/** What a one-finger touch on the canvas does: the tool in hand, or the 3D camera and planes. */
enum class CanvasGestureMode { PAINT, SCENE_3D }

/** The rail's 3D button decides it; EditorScreen mounts the tool surfaces or [Scene3DCanvas] by it. */
val EditorUiState.canvasGestureMode: CanvasGestureMode
    get() = if (is3DMode) CanvasGestureMode.SCENE_3D else CanvasGestureMode.PAINT

/** The view-model calls 3D mode makes, as an interface tests can fake. */
interface Scene3DActions {
    fun setTool(tool: Scene3DTool)
    fun orbit(delta: Offset)
    fun cameraSettled()
    fun resetCamera()
    fun planeStart()
    fun planeDrag(delta: Offset)
    fun planeEnd()
    fun resetPlane()
    fun spatialStroke(layerId: String, stroke: SpatialStroke)
    fun spatialErase(layerId: String, strokeId: String)
    fun guideChanged(layerId: String, guide: SpatialGuide)
}

fun EditorViewModel.scene3DActions(): Scene3DActions = object : Scene3DActions {
    override fun setTool(tool: Scene3DTool) = setScene3DTool(tool)
    override fun orbit(delta: Offset) = onSceneOrbit(delta)
    override fun cameraSettled() = onSceneCameraSettled()
    override fun resetCamera() = resetSceneCamera()
    override fun planeStart() = onPlaneGestureStart()
    override fun planeDrag(delta: Offset) = onPlaneDrag(delta)
    override fun planeEnd() = onPlaneGestureEnd()
    override fun resetPlane() = resetActivePlane()
    override fun spatialStroke(layerId: String, stroke: SpatialStroke) = onSpatialStroke(layerId, stroke)
    override fun spatialErase(layerId: String, strokeId: String) = onSpatialErase(layerId, strokeId)
    override fun guideChanged(layerId: String, guide: SpatialGuide) = onSpatialGuideChanged(layerId, guide)
}

/** A 3D strokes layer's stroke units in the scene, and the ray/guide frame in those units. */
internal class StrokeSpace(val toScene: Affine3, val frame: CameraFrame) {
    private val toLocal = toScene.inverse()

    fun project(p: SpatialPoint): Pair<Float, Float>? = frame.project(toScene.apply(p.x, p.y, p.z))

    /** The guide hit under world point ([x], [y]), in stroke units. */
    fun hit(guide: SpatialGuide, x: Float, y: Float, pressure: Float): SpatialPoint? {
        val inv = toLocal ?: return null
        val origin = inv.apply(frame.eye[0], frame.eye[1], frame.eye[2])
        val d = frame.rayDirection(x, y)
        val dir = SceneMath.normalize(inv.applyVector(d[0], d[1], d[2]))
        return SpatialGuideSurface.intersectRay(guide, view(), origin, dir, pressure)
    }

    fun view(): SpatialView {
        val inv = toLocal ?: Affine3.IDENTITY
        fun v(a: FloatArray) = SceneMath.normalize(inv.applyVector(a[0], a[1], a[2]))
        return SpatialView(
            inv.apply(frame.target[0], frame.target[1], frame.target[2]),
            v(frame.right),
            v(floatArrayOf(-frame.down[0], -frame.down[1], -frame.down[2])),
            v(frame.forward),
        )
    }

    /** Stroke width (units) whose tube is [diameterPx] world pixels across at the camera target. */
    fun widthFor(diameterPx: Float): Float {
        val distance = SceneMath.dot(
            FloatArray(3) { frame.target[it] - frame.eye[it] }, frame.forward,
        )
        val c = toScene.column(0)
        val unitPx = kotlin.math.sqrt(SceneMath.dot(c, c))
        return (diameterPx * distance / (frame.focal * unitPx)).coerceAtLeast(1e-4f)
    }

    companion object {
        fun of(state: EditorUiState, layer: Layer): StrokeSpace? {
            val size = state.canvasSize
            if (size.width <= 0 || size.height <= 0) return null
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val model = SceneMath.chainModel(planeChain(state.layers, layer.id), w, h) * SceneMath.strokeToLocal(w, h)
            return StrokeSpace(model, SceneMath.frame(state.sceneCamera, w, h))
        }
    }
}

/**
 * The canvas while 3D is on (gesture map in spec/3d-space.md): one finger orbits, moves, pushes,
 * tilts or spins the active plane, or draws/erases on a 3D strokes layer. Two fingers belong to
 * the root's navigation, which drives the scene camera in 3D, so this never consumes them.
 */
@Composable
internal fun Scene3DCanvas(
    uiState: EditorUiState,
    actions: Scene3DActions,
    draft: MutableState<Pair<String, SpatialStroke>?>,
    modifier: Modifier = Modifier,
) {
    val state by rememberUpdatedState(uiState)
    Box(
        modifier
            .testTag("scene3d.canvas")
            .pointerInput(actions) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val st = state
                    val tool = st.scene3DTool
                    val active = st.layers.firstOrNull { it.id == st.activeLayerId }
                    val spatial = active?.takeIf { it.spatial != null }
                    fun world(p: Offset) = CanvasHitTest.screenToWorld(p, st.viewportOffset, st.viewportZoom, st.viewportRotation)
                    val space = spatial?.let { StrokeSpace.of(st, it) }
                    val drawing = tool == Scene3DTool.DRAW && space != null
                    val erasing = tool == Scene3DTool.ERASE && space != null
                    val planeTool = tool in PLANE_TOOLS && active != null && !active.isImageLocked
                    val guide = spatial?.spatial?.guide
                    val width = space?.widthFor(st.brushSize / st.viewportZoom.coerceAtLeast(0.01f)) ?: 0f
                    val color = st.activeColor.toArgb()
                    var points = mutableListOf<SpatialPoint>()
                    fun commit() {
                        if (spatial != null && points.isNotEmpty()) {
                            actions.spatialStroke(spatial.id, SpatialStroke(UUID.randomUUID().toString(), points.toList(), color, width))
                        }
                        points = mutableListOf()
                        draft.value = null
                    }
                    fun sample(p: Offset, pressure: Float) {
                        val w = world(p)
                        val hit = guide?.let { space?.hit(it, w.x, w.y, pressure) }
                        if (hit == null) {
                            // A bounded guide has holes; never bridge one with a straight segment.
                            commit()
                        } else {
                            points.add(hit)
                            draft.value = spatial!!.id to SpatialStroke("live-preview", points.toList(), color, width)
                        }
                    }
                    fun erase(p: Offset) {
                        val w = world(p)
                        val strokes = spatial?.spatial?.strokes ?: return
                        SpatialSketchGeometry.nearestStrokeId(strokes, w.x, w.y, 24f / st.viewportZoom.coerceAtLeast(0.01f)) {
                            space?.project(it)
                        }?.let { actions.spatialErase(spatial.id, it) }
                    }
                    if (drawing) sample(down.position, down.pressure)
                    if (erasing) erase(down.position)
                    var dragging = false
                    var navigated = false
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // Two fingers: the camera (root navigation). A curve in progress ends.
                            if (drawing) commit()
                            if (dragging && planeTool) actions.planeEnd()
                            dragging = false
                            navigated = true
                            continue
                        }
                        if (navigated) continue
                        val change = pressed.first()
                        val delta = change.position - last
                        last = change.position
                        when {
                            drawing -> { sample(change.position, change.pressure); change.consume() }
                            erasing -> { erase(change.position); change.consume() }
                            else -> {
                                if (!dragging) {
                                    if ((change.position - down.position).getDistance() < slop) continue
                                    dragging = true
                                    if (planeTool) actions.planeStart()
                                }
                                val worldDelta = CanvasHitTest.screenDeltaToWorld(delta, st.viewportZoom, st.viewportRotation)
                                if (planeTool) actions.planeDrag(worldDelta) else if (tool == Scene3DTool.ORBIT) actions.orbit(delta)
                                change.consume()
                            }
                        }
                    }
                    if (drawing) commit()
                    if (dragging && planeTool) actions.planeEnd()
                    if ((dragging && tool == Scene3DTool.ORBIT) || navigated) actions.cameraSettled()
                }
            },
    )
}

private val PLANE_TOOLS = setOf(Scene3DTool.MOVE, Scene3DTool.DEPTH, Scene3DTool.TILT, Scene3DTool.SPIN)

/**
 * A 3D strokes layer in the stack: its tubes through the GLES renderer at the layer's placement,
 * with the live curve and, while drawing with 3D on, the guide's wireframe.
 */
@Composable
internal fun SpatialLayerNode(layer: Layer, scene: SceneContext?, frameAlpha: Float) {
    val content = layer.spatial ?: return
    if (scene == null) return
    val frame = scene.frame
    val toScene = scene.modelFor(layer) * SceneMath.strokeToLocal(frame.width, frame.height)
    val strokes = remember(content.strokes, scene.draft, scene.draftLayerId) {
        val draft = scene.draft?.takeIf { scene.draftLayerId == layer.id }
        if (draft == null) content.strokes else content.strokes + draft
    }
    val clip = SceneMath.clipMatrix(toScene, frame)
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = layer.opacity * frameAlpha }) {
        SpatialInkLayer(strokes, clip, Modifier.fillMaxSize())
        if (content.guide.visible && scene.showGuideFor == layer.id) {
            val space = StrokeSpace(toScene, frame)
            val lines = remember(content.guide, scene.camera, toScene.m.contentHashCode()) {
                SpatialGuideSurface.wireframe(content.guide, space.view()).mapNotNull { (a, b) ->
                    val p = space.project(a) ?: return@mapNotNull null
                    val q = space.project(b) ?: return@mapNotNull null
                    Offset(p.first, p.second) to Offset(q.first, q.second)
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                lines.forEach { (a, b) -> drawLine(Color.White.copy(alpha = 0.18f), a, b, strokeWidth = 1f) }
            }
        }
    }
}

/**
 * The 3D mode strip: what one finger does, Reset view and Reset plane, and on a 3D strokes layer
 * Draw, Erase and its guide (kind, and one parameter stepped at a time).
 */
@Composable
internal fun Scene3DToolbar(uiState: EditorUiState, actions: Scene3DActions, modifier: Modifier = Modifier) {
    val active = uiState.layers.firstOrNull { it.id == uiState.activeLayerId }
    val spatial = active?.takeIf { it.spatial != null }
    val tools = buildList {
        add(Scene3DTool.ORBIT)
        if (active != null) addAll(listOf(Scene3DTool.MOVE, Scene3DTool.DEPTH, Scene3DTool.TILT, Scene3DTool.SPIN))
        if (spatial != null) addAll(listOf(Scene3DTool.DRAW, Scene3DTool.ERASE))
    }
    Column(
        modifier
            .background(Color(0xE6101015), RoundedCornerShape(14.dp))
            .padding(horizontal = 4.dp)
            .testTag("scene3d.toolbar"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            for (tool in tools) {
                val on = uiState.scene3DTool == tool
                TextButton(onClick = { actions.setTool(tool) }, modifier = Modifier.testTag("scene3d.tool.${tool.name.lowercase()}")) {
                    Text(TOOL_LABELS.getValue(tool), color = if (on) Color.White else Color(0xFF9A9AA2))
                }
            }
            TextButton(onClick = actions::resetCamera, modifier = Modifier.testTag("scene3d.resetView")) {
                Text("Reset view", color = Color(0xFF9A9AA2))
            }
            if (active?.let { it.positionZ != 0f || it.rotationX != 0f || it.rotationY != 0f } == true) {
                TextButton(onClick = actions::resetPlane, modifier = Modifier.testTag("scene3d.resetPlane")) {
                    Text("Reset plane", color = Color(0xFF9A9AA2))
                }
            }
        }
        val content = spatial?.spatial
        if (content != null && uiState.scene3DTool == Scene3DTool.DRAW) {
            GuideControls(content.guide) { actions.guideChanged(spatial.id, it) }
        }
    }
}

private val TOOL_LABELS = mapOf(
    Scene3DTool.ORBIT to "Orbit",
    Scene3DTool.MOVE to "Move",
    Scene3DTool.DEPTH to "Depth",
    Scene3DTool.TILT to "Tilt",
    Scene3DTool.SPIN to "Spin",
    Scene3DTool.DRAW to "Draw",
    Scene3DTool.ERASE to "Erase",
)

/** The edited guide parameter; the others are kept during a step. */
internal enum class GuideParameter { DEPTH, X, Y, Z, RADIUS, HEIGHT, YAW, PITCH }

/** A constrained, reversible step of one guide parameter, leaving committed strokes untouched. */
internal fun adjustGuide(guide: SpatialGuide, parameter: GuideParameter, direction: Float): SpatialGuide {
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

/** The retired Spatial Sketch workspace's guide controls, now the 3D strokes layer's. */
@Composable
private fun GuideControls(guide: SpatialGuide, onChange: (SpatialGuide) -> Unit) {
    var parameter by remember(guide.kind) {
        mutableStateOf(if (guide.kind == SpatialGuideKind.VIEW_PLANE) GuideParameter.DEPTH else GuideParameter.RADIUS)
    }
    val dim = Color(0xFF9A9AA2)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            for (kind in SpatialGuideKind.entries) {
                val label = when (kind) {
                    SpatialGuideKind.VIEW_PLANE -> "View"
                    SpatialGuideKind.PLANE -> "Plane"
                    SpatialGuideKind.CYLINDER -> "Cylinder"
                    SpatialGuideKind.SPHERE -> "Sphere"
                }
                TextButton(onClick = { onChange(guide.copy(kind = kind)) }) {
                    Text(label, color = if (kind == guide.kind) Color.White else dim)
                }
            }
            TextButton(onClick = { onChange(guide.copy(visible = !guide.visible)) }) {
                Text(if (guide.visible) "Hide guide" else "Show guide", color = dim)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            val options = if (guide.kind == SpatialGuideKind.VIEW_PLANE) {
                listOf(GuideParameter.DEPTH, GuideParameter.X, GuideParameter.Y, GuideParameter.Z, GuideParameter.RADIUS)
            } else {
                listOf(GuideParameter.X, GuideParameter.Y, GuideParameter.Z, GuideParameter.RADIUS) +
                    (if (guide.kind == SpatialGuideKind.CYLINDER) listOf(GuideParameter.HEIGHT) else emptyList()) +
                    (if (guide.kind != SpatialGuideKind.SPHERE) listOf(GuideParameter.YAW, GuideParameter.PITCH) else emptyList())
            }
            for (option in options) {
                TextButton(onClick = { parameter = option }) {
                    Text(option.name.lowercase(), color = if (parameter == option) Color.White else dim)
                }
            }
            val value = when (parameter) {
                GuideParameter.DEPTH -> guide.depth
                GuideParameter.X -> guide.center.x
                GuideParameter.Y -> guide.center.y
                GuideParameter.Z -> guide.center.z
                GuideParameter.RADIUS -> guide.radius
                GuideParameter.HEIGHT -> guide.height
                GuideParameter.YAW -> Math.toDegrees(guide.yaw.toDouble()).toFloat()
                GuideParameter.PITCH -> Math.toDegrees(guide.pitch.toDouble()).toFloat()
            }
            TextButton(onClick = { onChange(adjustGuide(guide, parameter, -1f)) }) { Text("−", color = Color.White) }
            Text("%.2f".format(value), color = Color.LightGray)
            TextButton(onClick = { onChange(adjustGuide(guide, parameter, 1f)) }) { Text("+", color = Color.White) }
            TextButton(onClick = { onChange(SpatialGuide(kind = guide.kind)) }) { Text("Reset guide", color = dim) }
        }
    }
}
