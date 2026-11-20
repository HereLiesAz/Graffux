package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.ShapeToolMode
import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Shape tool's rail circle, its long-press menu and its option chips. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ShapeToolUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val tools = mutableListOf<Tool>()
    private val picked = mutableListOf<ShapeToolMode>()
    private val railActions = object : ToolRailActions {
        override fun setActiveTool(tool: Tool) { tools += tool }
        override fun onColorClicked() = Unit
        override fun resetViewport() = Unit
        override fun onUndoClicked() = Unit
        override fun onRedoClicked() = Unit
        override fun setBrushSize(size: Float) = Unit
        override fun setBrushFeathering(feathering: Float) = Unit
    }

    private fun rail(state: EditorUiState) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    ToolRail(
                        vm = railActions,
                        uiState = state,
                        lit = emptySet(),
                        secondary = emptySet(),
                        callbacks = ToolRailCallbacks({}, {}, {}, onShapeMode = { picked += it }),
                    )
                }
            }
        }
    }

    @Test
    fun `the rail circle keeps its tag, says Shape and the mode, and still picks the tool up and puts it down`() {
        rail(EditorUiState())
        rule.onNodeWithTag("rail.tool.frame").assertContentDescriptionEquals("Shape, Frame")
        rule.onNodeWithTag("rail.tool.frame").performClick()
        assertEquals(listOf(Tool.FRAME), tools)
    }

    @Test
    fun `a tap with the tool in hand puts it down`() {
        rail(EditorUiState(activeTool = Tool.FRAME))
        rule.onNodeWithTag("rail.tool.frame").performClick()
        assertEquals(listOf(Tool.NONE), tools)
    }

    @Test
    fun `the circle's description follows the mode`() {
        rail(EditorUiState(shapeToolMode = ShapeToolMode.ROUNDED_RECTANGLE))
        rule.onNodeWithTag("rail.tool.frame").assertContentDescriptionEquals("Shape, Rounded rectangle")
    }

    @Test
    fun `a long-press says what it does and opens the modes, one checked, each at least 48dp`() {
        rail(EditorUiState(shapeToolMode = ShapeToolMode.ELLIPSE))
        val node = rule.onNodeWithTag("rail.tool.frame")
        val longClick = node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnLongClick)
        assertEquals("Choose shape type", longClick?.label)
        node.performTouchInput { longClick() }
        ShapeToolMode.OFFERED.forEach { mode ->
            val row = rule.onNodeWithTag("shape.mode.${mode.name.lowercase()}")
            row.assertHeightIsAtLeast(48.dp)
            assertEquals(Role.RadioButton, row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
            val config = row.fetchSemanticsNode().config
            assertEquals(mode == ShapeToolMode.ELLIPSE, config.getOrNull(SemanticsProperties.Selected))
        }
        assertEquals(5, ShapeToolMode.OFFERED.size)
    }

    @Test
    fun `choosing a mode from the menu picks the tool up first when it is not in hand`() {
        rail(EditorUiState())
        rule.onNodeWithTag("rail.tool.frame").performTouchInput { longClick() }
        rule.onNodeWithTag("shape.mode.line").performClick()
        assertEquals(listOf(ShapeToolMode.LINE), picked)
        assertEquals(listOf(Tool.FRAME), tools)
    }

    @Test
    fun `choosing a mode with the tool already in hand does not toggle it`() {
        rail(EditorUiState(activeTool = Tool.FRAME))
        rule.onNodeWithTag("rail.tool.frame").performTouchInput { longClick() }
        rule.onNodeWithTag("shape.mode.rectangle").performClick()
        assertEquals(listOf(ShapeToolMode.RECTANGLE), picked)
        assertTrue(tools.isEmpty())
    }

    private class Recorder : DesignActions {
        var mode by mutableStateOf(ShapeToolMode.FRAME)
        val calls = mutableListOf<String>()
        override fun setBox(x: Float, y: Float, width: Float, height: Float) = Unit
        override fun setRotation(degrees: Float) = Unit
        override fun setOpacityPercent(percent: Float) = Unit
        override fun setRadius(radius: Float) = Unit
        override fun setClip(clips: Boolean) = Unit
        override fun setFill(argb: Long?) = Unit
        override fun fillWithColorInHand() = Unit
        override fun addFrame() { calls += "add frame" }
        override fun setShapeMode(mode: ShapeToolMode) { calls += "mode $mode"; this.mode = mode }
        override fun addShape() { calls += "add shape" }
    }

    private fun chips(recorder: Recorder) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) { ShapeModeRow(recorder.mode, recorder) }
            }
        }
    }

    @Test
    fun `the option chips are radios, one selected, and picking one asks for that mode`() {
        val recorder = Recorder()
        chips(recorder)
        ShapeToolMode.OFFERED.forEach { mode ->
            val chip = rule.onNodeWithTag("design.shapeMode.${mode.name.lowercase()}")
            assertEquals(Role.RadioButton, chip.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        }
        rule.onNodeWithTag("design.shapeMode.frame").assertIsSelected()
        rule.onNodeWithTag("design.shapeMode.ellipse").performClick()
        rule.waitForIdle()
        assertEquals(listOf("mode ELLIPSE"), recorder.calls)
        rule.onNodeWithTag("design.shapeMode.ellipse").assertIsSelected()
    }

    @Test
    fun `Add shape needs no pointer drag and appears only in a shape mode`() {
        val recorder = Recorder()
        chips(recorder)
        assertTrue(absent("design.addShape"))
        rule.onNodeWithTag("design.shapeMode.line").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("design.addShape").performScrollTo().performClick()
        assertEquals(listOf("mode LINE", "add shape"), recorder.calls)
    }

    private fun absent(tag: String): Boolean = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
}
