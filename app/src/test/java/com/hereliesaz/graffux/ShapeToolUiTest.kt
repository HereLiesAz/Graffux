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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** Shape modes live under the Vector group; the Design sheet remains their editing surface. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ShapeToolUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val tools = mutableListOf<Tool>()
    private val opened = mutableListOf<CarouselCategory>()
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
                        callbacks = ToolRailCallbacks({}, { opened += it }, {}),
                    )
                }
            }
        }
    }

    @Test
    fun `the Vector rail button replaces the separate Shape button`() {
        rail(EditorUiState())
        assertTrue(absent("rail.tool.frame"))
        rule.onNodeWithTag("rail.grp.vector").performClick()
        assertEquals(listOf(CarouselCategory.VECTOR), opened)
    }

    @Test
    fun `Vector lists the frame tool and all five available shape modes`() {
        val entries = toolGroupCarouselEntries(CarouselCategory.VECTOR, EditorUiState())
        assertTrue(entries.any { it.action == CarouselAction.PickTool(Tool.PEN) })
        assertTrue(entries.any { it.action == CarouselAction.PickTool(Tool.FRAME) })
        val modes = entries.mapNotNull { (it.action as? CarouselAction.ShapeMode)?.mode }
        assertEquals(
            listOf(ShapeToolMode.FRAME, ShapeToolMode.RECTANGLE, ShapeToolMode.ROUNDED_RECTANGLE,
                ShapeToolMode.ELLIPSE, ShapeToolMode.LINE),
            modes,
        )
    }

    @Test
    fun `only the active shape mode is selected inside Vector`() {
        val entries = toolGroupCarouselEntries(
            CarouselCategory.VECTOR,
            EditorUiState(activeTool = Tool.FRAME, shapeToolMode = ShapeToolMode.ELLIPSE),
        )
        val selectedModes = entries.filter {
            it.action is CarouselAction.ShapeMode && it.selected
        }.map { it.key }
        assertEquals(listOf("shape.mode.ELLIPSE"), selectedModes)
        assertTrue(entries.any { it.key == "shape.mode.RECTANGLE" && !it.selected })
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
