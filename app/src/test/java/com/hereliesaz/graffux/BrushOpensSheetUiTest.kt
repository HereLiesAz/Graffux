package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Picking up the brush, however it happens, puts up the brush sheet with its carousel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class BrushOpensSheetUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var tool by mutableStateOf(Tool.NONE)
    private var ui by mutableStateOf(CarouselUi(CarouselCategory.BRUSHES, sheetOpen = false, showingCarousel = false))

    private val actions = object : ToolRailActions {
        override fun setActiveTool(tool: Tool) { this@BrushOpensSheetUiTest.tool = tool }
        override fun onColorClicked() = Unit
        override fun resetViewport() = Unit
        override fun onUndoClicked() = Unit
        override fun onRedoClicked() = Unit
        override fun setBrushSize(size: Float) = Unit
        override fun setBrushFeathering(feathering: Float) = Unit
    }

    /** The same wiring MainActivity has: the rail's onBrush and the active-tool effect. */
    private fun setUp() {
        rule.setContent {
            val open = { ui = ui.openOnBrushes() }
            brushOpensSheet(tool, open)
            MaterialTheme(colorScheme = darkColorScheme()) {
                ToolRail(
                    vm = actions,
                    uiState = EditorUiState(activeTool = tool),
                    lit = emptySet(),
                    secondary = emptySet(),
                    callbacks = ToolRailCallbacks(
                        onToolOptions = {},
                        onToolGroup = { ui = ui.copy(category = it, sheetOpen = true, tab = null) },
                        onBrushMenu = {},
                        onBrush = open,
                    ),
                )
            }
        }
        rule.waitForIdle()
    }

    private fun assertBrushSheetShown() {
        assertTrue(ui.sheetOpen)
        assertTrue(ui.showingCarousel)
        assertFalse(ui.photo)
        assertEquals(CarouselCategory.BRUSHES, ui.category)
    }

    @Test
    fun brushTapOpensTheSheetOnTheBrushPage() {
        setUp()
        assertFalse(ui.sheetOpen)
        rule.onNodeWithTag("rail.tool.brush").performClick()
        rule.waitForIdle()
        assertEquals(Tool.BRUSH, tool)
        assertBrushSheetShown()
    }

    @Test
    fun reTapWhileActiveReopensAfterADismiss() {
        setUp()
        rule.onNodeWithTag("rail.tool.brush").performClick()
        rule.waitForIdle()
        ui = ui.copy(sheetOpen = false, showingCarousel = false)
        rule.waitForIdle()
        rule.onNodeWithTag("rail.tool.brush").performClick()
        rule.waitForIdle()
        assertEquals(Tool.BRUSH, tool)
        assertBrushSheetShown()
    }

    @Test
    fun switchingFromAnotherToolOpensIt() {
        setUp()
        rule.onNodeWithTag("rail.tool.fill").performClick()
        rule.onNodeWithTag("rail.$RETOUCH_RAIL_ID").performClick()
        rule.waitForIdle()
        assertEquals(CarouselCategory.EFFECTS, ui.category)
        ui = ui.copy(sheetOpen = false)
        // Not through the rail: undo, a shortcut or another tool handing back sets the tool directly.
        tool = Tool.BRUSH
        rule.waitForIdle()
        assertBrushSheetShown()
    }
}
