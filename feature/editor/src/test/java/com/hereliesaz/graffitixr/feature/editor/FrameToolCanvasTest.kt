package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.FrameOps
import com.hereliesaz.graffitixr.common.model.Rect
import com.hereliesaz.graffitixr.common.model.ShapeToolMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Frame tool's touches, on a fake view-model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class FrameToolCanvasTest {

    @get:Rule
    val rule = createComposeRule()

    private class Recorder(private val hit: String? = null, private val grab: String? = hit) : FrameToolActions {
        val calls = mutableListOf<String>()
        override fun hitAt(screen: Offset, canvasWidth: Float, canvasHeight: Float): String? = hit
        override fun dragTargetAt(mode: ShapeToolMode, screen: Offset, canvasWidth: Float, canvasHeight: Float) =
            if (mode.drawsShape) grab else hit
        override fun commitShape(mode: ShapeToolMode, start: Offset, end: Offset, w: Float, h: Float) {
            calls += "shape ${mode.name}"
        }
        override fun tap(screen: Offset, canvasWidth: Float, canvasHeight: Float) { calls += "tap" }
        override fun commitFrame(start: Offset, end: Offset, canvasWidth: Float, canvasHeight: Float) {
            calls += "frame"
        }
        override fun dragStart(layerId: String) { calls += "start" }
        override fun move(layerId: String, screenDelta: Offset) { calls += "move" }
        override fun resize(frameId: String, box: Rect) { calls += "resize" }
        override fun dragEnd(layerId: String, moved: Boolean) { calls += "end" }
    }

    private var shown by mutableStateOf<Pair<Recorder, EditorUiState>?>(null)
    private var composed = false

    /** Shows the canvas for [recorder] and [state]; a later call swaps them in (the rule composes once per test). */
    private fun canvas(recorder: Recorder, state: EditorUiState = EditorUiState()) {
        shown = recorder to state
        if (composed) {
            rule.waitForIdle()
            return
        }
        composed = true
        rule.setContent {
            shown?.let { (r, st) -> FrameToolCanvas(st, r, Modifier.fillMaxSize()) }
        }
    }

    private fun drag(from: Offset, to: Offset) {
        rule.onNodeWithTag("design.frameTool").performTouchInput {
            down(0, from)
            moveTo(0, to)
            up(0)
        }
    }

    private val drawn = listOf(
        ShapeToolMode.RECTANGLE, ShapeToolMode.ROUNDED_RECTANGLE, ShapeToolMode.ELLIPSE, ShapeToolMode.LINE,
    )

    @Test
    fun `one finger dragged over empty canvas draws a frame`() {
        val recorder = Recorder()
        canvas(recorder)
        rule.onNodeWithTag("design.frameTool").performTouchInput {
            down(0, Offset(100f, 100f))
            moveTo(0, Offset(300f, 400f))
            up(0)
        }
        assertEquals(listOf("frame"), recorder.calls)
    }

    @Test
    fun `a second finger for a pan or pinch draws nothing`() {
        val recorder = Recorder()
        canvas(recorder)
        rule.onNodeWithTag("design.frameTool").performTouchInput {
            down(0, Offset(100f, 100f))
            moveTo(0, Offset(200f, 200f))
            down(1, Offset(300f, 300f))
            moveTo(1, Offset(340f, 340f))
            up(1)
            up(0)
        }
        assertEquals(recorder.calls.toString(), 0, recorder.calls.count { it == "frame" })
    }

    @Test
    fun `in each shape mode a drag on empty canvas draws that shape and not a frame`() {
        for (mode in drawn) {
            val recorder = Recorder()
            canvas(recorder, EditorUiState(shapeToolMode = mode))
            drag(Offset(100f, 100f), Offset(300f, 400f))
            assertEquals(mode.name, listOf("shape ${mode.name}"), recorder.calls)
        }
    }

    @Test
    fun `in each mode a drag from a layer's middle moves it, and draws nothing`() {
        for (mode in ShapeToolMode.OFFERED) {
            val recorder = Recorder(hit = "art")
            canvas(recorder, EditorUiState(shapeToolMode = mode))
            drag(Offset(100f, 100f), Offset(300f, 400f))
            assertEquals(mode.name, listOf("start", "move", "end"), recorder.calls.distinct())
        }
    }

    @Test
    fun `in each mode a touch that stays within the slop is a tap, not a shape`() {
        for (mode in ShapeToolMode.OFFERED) {
            val recorder = Recorder()
            canvas(recorder, EditorUiState(shapeToolMode = mode))
            drag(Offset(100f, 100f), Offset(102f, 101f))
            assertEquals(mode.name, listOf("tap"), recorder.calls)
        }
    }

    @Test
    fun `in a shape mode a second finger is still the camera's`() {
        val recorder = Recorder()
        canvas(recorder, EditorUiState(shapeToolMode = ShapeToolMode.ELLIPSE))
        rule.onNodeWithTag("design.frameTool").performTouchInput {
            down(0, Offset(100f, 100f))
            moveTo(0, Offset(200f, 200f))
            down(1, Offset(300f, 300f))
            moveTo(1, Offset(340f, 340f))
            up(1)
            up(0)
        }
        assertEquals(0, recorder.calls.count { it.startsWith("shape") || it == "frame" })
    }

    @Test
    fun `a selected frame's corner handle resizes it in every mode`() {
        val frame = FrameOps.create("F", "F", Rect(-100f, -100f, 200f, 200f))
        for (mode in ShapeToolMode.OFFERED) {
            val state = EditorUiState(layers = listOf(frame), activeLayerId = "F", shapeToolMode = mode)
            val recorder = Recorder()
            canvas(recorder, state)
            val size = rule.onNodeWithTag("design.frameTool").fetchSemanticsNode().size
            val corner = frameScreenCorners(state, frame, size.width.toFloat(), size.height.toFloat())[2]
            drag(corner, corner + Offset(40f, 40f))
            assertEquals(mode.name, listOf("start", "resize", "end"), recorder.calls.distinct())
        }
    }

    @Test
    fun `in a shape mode a drag that starts on a photo draws, though a tap there would select it`() {
        for (mode in drawn) {
            val recorder = Recorder(hit = "photo", grab = null)
            canvas(recorder, EditorUiState(shapeToolMode = mode))
            drag(Offset(100f, 100f), Offset(300f, 400f))
            assertEquals(mode.name, listOf("shape ${mode.name}"), recorder.calls)
        }
    }

    @Test
    fun `in Frame mode a drag that starts on a layer still moves it`() {
        val recorder = Recorder(hit = "photo", grab = null)
        canvas(recorder, EditorUiState(shapeToolMode = ShapeToolMode.FRAME))
        drag(Offset(100f, 100f), Offset(300f, 400f))
        assertEquals(listOf("start", "move", "end"), recorder.calls.distinct())
    }
}
