@file:Suppress("MagicNumber", "MaxLineLength", "FunctionNaming", "CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements")

package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.FrameOps
import com.hereliesaz.graffitixr.common.model.Rect
import com.hereliesaz.graffitixr.common.model.isFrame
import kotlin.math.abs
import kotlin.math.roundToInt

/** The design overlay's colours, from the mockup's "Design tools" boards. */
private val LabelInk = Color(0xFF8A8A90)
private val SelectedInk = Color(0xFFEDEDED)
private val CanvasInk = Color(0xFF0E0E0F)
private val BadgeFill = Color(0xFF3A3A3F)

/** What the Frame tool asks of the editor. The view-model's design calls, as an interface tests can fake. */
internal interface FrameToolActions {
    fun hitAt(screen: Offset, canvasWidth: Float, canvasHeight: Float): String?
    fun tap(screen: Offset, canvasWidth: Float, canvasHeight: Float)
    fun commitFrame(start: Offset, end: Offset, canvasWidth: Float, canvasHeight: Float)
    fun dragStart(layerId: String)
    fun move(layerId: String, screenDelta: Offset)
    fun resize(frameId: String, box: Rect)
    fun dragEnd(layerId: String, moved: Boolean)
}

internal fun EditorViewModel.frameToolActions(): FrameToolActions = object : FrameToolActions {
    override fun hitAt(screen: Offset, canvasWidth: Float, canvasHeight: Float) = designHitAt(screen, canvasWidth, canvasHeight)
    override fun tap(screen: Offset, canvasWidth: Float, canvasHeight: Float) = onDesignTap(screen, canvasWidth, canvasHeight)
    override fun commitFrame(start: Offset, end: Offset, canvasWidth: Float, canvasHeight: Float) =
        onCommitFrame(start, end, canvasWidth, canvasHeight)
    override fun dragStart(layerId: String) = onDesignDragStart(layerId)
    override fun move(layerId: String, screenDelta: Offset) = onDesignMove(layerId, screenDelta)
    override fun resize(frameId: String, box: Rect) = onDesignResize(frameId, box)
    override fun dragEnd(layerId: String, moved: Boolean) = onDesignDragEnd(layerId, moved)
}

/**
 * The frames on the canvas, drawn in screen space over the artwork: each top-level frame's name
 * above its top-left corner (Figma's frame labels), and, with the Frame tool in hand, the selected
 * layer's outline, its size, and a frame's four resize handles. Purely visual; [FrameToolCanvas]
 * takes the touches.
 */
@Composable
internal fun FrameOverlay(uiState: EditorUiState, designMode: Boolean, modifier: Modifier = Modifier) {
    val frames = uiState.layers.filter { it.isFrame && it.isVisible && it.parentId == null }
    val activeId = uiState.activeLayerId
    if (frames.isEmpty() && !designMode) return
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    Canvas(modifier.testTag("design.frames")) {
        val cw = size.width
        val ch = size.height
        fun corners(box: Rect) = DesignGeometry.screenCorners(
            box, cw, ch, uiState.viewportOffset, uiState.viewportZoom, uiState.viewportRotation,
        )
        frames.forEach { frame ->
            val tl = corners(FrameOps.boxOf(uiState.layers, frame)).first()
            val color = if (frame.id == activeId && designMode) SelectedInk else LabelInk
            val text = measurer.measure(frame.name, labelStyle.copy(color = color))
            drawText(text, topLeft = Offset(tl.x, tl.y - text.size.height - 4.dp.toPx()))
        }
        if (!designMode || activeId == null) return@Canvas
        val active = uiState.layers.firstOrNull { it.id == activeId } ?: return@Canvas
        val box = DesignGeometry.box(uiState.layers, activeId, cw, ch) ?: return@Canvas
        val pts = corners(box)
        drawOutline(pts)
        if (active.isFrame) pts.forEach { drawHandle(it) }
        // The size badge, under the box (the mockup's "218 × 70").
        val badge = measurer.measure(
            "${box.width.roundToInt()} × ${box.height.roundToInt()}",
            labelStyle.copy(color = SelectedInk),
        )
        val bottom = pts.maxOf { it.y }
        val midX = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
        val pad = 4.dp.toPx()
        val topLeft = Offset(midX - badge.size.width / 2f - pad, bottom + 6.dp.toPx())
        drawRect(BadgeFill, topLeft, Size(badge.size.width + pad * 2, badge.size.height.toFloat()))
        drawText(badge, topLeft = Offset(topLeft.x + pad, topLeft.y))
    }
}

private fun DrawScope.drawOutline(pts: List<Offset>) {
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, SelectedInk, style = Stroke(width = 1.5.dp.toPx()))
}

private fun DrawScope.drawHandle(at: Offset) {
    val s = 8.dp.toPx()
    drawRect(CanvasInk, Offset(at.x - s / 2f, at.y - s / 2f), Size(s, s))
    drawRect(SelectedInk, Offset(at.x - s / 2f, at.y - s / 2f), Size(s, s), style = Stroke(width = 1.dp.toPx()))
}

/**
 * The Frame tool's touches. One finger: a tap selects what is under it (or clears the
 * selection); a drag on empty canvas draws a new frame; a drag on a layer moves it (dropping it
 * into or out of a frame on release); a drag on a selected frame's corner handle resizes the frame,
 * its children following their constraints. A second finger is left to the camera. A drag hides
 * the chrome as a stroke does ([gate]); a tap does not, so the properties stay up to show it.
 */
@Composable
internal fun FrameToolCanvas(
    uiState: EditorUiState,
    actions: FrameToolActions,
    modifier: Modifier = Modifier,
    gate: StrokeGate? = null,
) {
    val state by rememberUpdatedState(uiState)
    var drawing by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    Canvas(
        modifier
            .testTag("design.frameTool")
            .pointerInput(actions) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val start = down.position
                    val st = state
                    val handle = handleUnder(st, start, w, h, 24.dp.toPx())
                    val target = handle?.first ?: actions.hitAt(start, w, h)
                    val slop = viewConfiguration.touchSlop
                    var dragging = false
                    var last = start
                    var current = start
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) break
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        current = change.position
                        if (!dragging && (current - start).getDistance() > slop) {
                            dragging = true
                            // A drag is a stroke for the chrome: everything hides until it lifts.
                            gate?.strokeActive = true
                            if (target != null) actions.dragStart(target)
                        }
                        if (dragging) {
                            change.consume()
                            when {
                                handle != null -> actions.resize(handle.first, resizedBox(st, handle.second, current, w, h))
                                target != null -> actions.move(target, current - last)
                                else -> drawing = start to current
                            }
                        }
                        last = current
                    }
                    when {
                        !dragging -> actions.tap(start, w, h)
                        target != null -> actions.dragEnd(target, moved = handle == null)
                        else -> actions.commitFrame(start, current, w, h)
                    }
                    drawing = null
                    gate?.strokeActive = false
                }
            },
    ) {
        drawing?.let { (a, b) ->
            val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
            val sz = Size(abs(b.x - a.x), abs(b.y - a.y))
            drawRect(Color.White.copy(alpha = 0.06f), tl, sz)
            drawRect(SelectedInk, tl, sz, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

/** The selected frame's corner under [p] (index 0..3, TL TR BR BL), within [tolerance] screen px. */
private fun handleUnder(st: EditorUiState, p: Offset, w: Float, h: Float, tolerance: Float): Pair<String, Int>? {
    val id = st.activeLayerId ?: return null
    val frame = st.layers.firstOrNull { it.id == id }?.takeIf { it.isFrame } ?: return null
    val pts = DesignGeometry.screenCorners(
        FrameOps.boxOf(st.layers, frame), w, h, st.viewportOffset, st.viewportZoom, st.viewportRotation,
    )
    val index = pts.indices.minByOrNull { (pts[it] - p).getDistance() } ?: return null
    return if ((pts[index] - p).getDistance() <= tolerance) id to index else null
}

/** The frame's box with corner [corner] dragged to screen point [to]; the opposite corner stays put. */
private fun resizedBox(st: EditorUiState, corner: Int, to: Offset, w: Float, h: Float): Rect {
    val frame = st.layers.first { it.id == st.activeLayerId }
    val box = FrameOps.boxOf(st.layers, frame)
    val opposite = when (corner) {
        0 -> Offset(box.right, box.bottom)
        1 -> Offset(box.x, box.bottom)
        2 -> Offset(box.x, box.y)
        else -> Offset(box.right, box.y)
    }
    val world = CanvasHitTest.screenToWorld(to, st.viewportOffset, st.viewportZoom, st.viewportRotation)
        .let { Offset(it.x - w / 2f, it.y - h / 2f) }
    return Rect(minOf(opposite.x, world.x), minOf(opposite.y, world.y), abs(world.x - opposite.x), abs(world.y - opposite.y))
}
