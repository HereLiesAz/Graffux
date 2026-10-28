package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Rect
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

    private class Recorder : FrameToolActions {
        val calls = mutableListOf<String>()
        override fun hitAt(screen: Offset, canvasWidth: Float, canvasHeight: Float): String? = null
        override fun tap(screen: Offset, canvasWidth: Float, canvasHeight: Float) { calls += "tap" }
        override fun commitFrame(start: Offset, end: Offset, canvasWidth: Float, canvasHeight: Float) {
            calls += "frame"
        }
        override fun dragStart(layerId: String) { calls += "start" }
        override fun move(layerId: String, screenDelta: Offset) { calls += "move" }
        override fun resize(frameId: String, box: Rect) { calls += "resize" }
        override fun dragEnd(layerId: String, moved: Boolean) { calls += "end" }
    }

    private fun canvas(recorder: Recorder) = rule.setContent {
        FrameToolCanvas(EditorUiState(), recorder, Modifier.fillMaxSize())
    }

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
}
