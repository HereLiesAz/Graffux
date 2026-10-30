package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The rail reaches the brush and the retouch tools, and is the only way into the brush library. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class RailToolGroupsUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun railHasBrushAndRetouchGroup() {
        val tools = mutableListOf<Tool>()
        val groups = mutableListOf<CarouselCategory>()
        val actions = object : ToolRailActions {
            override fun setActiveTool(tool: Tool) { tools += tool }
            override fun onColorClicked() = Unit
            override fun resetViewport() = Unit
            override fun onUndoClicked() = Unit
            override fun onRedoClicked() = Unit
            override fun setBrushSize(size: Float) = Unit
            override fun setBrushFeathering(feathering: Float) = Unit
        }
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ToolRail(
                    vm = actions,
                    uiState = EditorUiState(),
                    lit = emptySet(),
                    secondary = emptySet(),
                    callbacks = ToolRailCallbacks(onToolOptions = {}, onToolGroup = { groups += it }, onBrushMenu = {}),
                )
            }
        }
        rule.onNodeWithTag("rail.tool.brush").performClick()
        rule.onNodeWithTag("rail.$RETOUCH_RAIL_ID").performClick()
        assertEquals(listOf(Tool.BRUSH), tools)
        assertEquals(listOf(CarouselCategory.EFFECTS), groups)
    }

    @Test
    fun retouchLightsWhileAnEffectToolIsInHand() {
        EFFECT_TOOLS.forEach { tool ->
            val lit = activeRailClassifiers(
                RailClassifierInputs(
                    EditorUiState(activeTool = tool), emptyList(), emptyList(), modelWindowOpen = false,
                ),
            )
            assertTrue("$tool", RETOUCH_RAIL_ID in lit)
        }
        assertEquals(RETOUCH_RAIL_ID, carouselGroupRailId(CarouselCategory.EFFECTS))
    }

    /**
     * The Favourites / Recent / Most used shortcuts sheet is gone: nothing in the rail opens it and
     * no swipe strip for it hangs off the bottom edge. The brush library is reached from the rail.
     */
    @Test
    fun noShortcutsSheetAnywhereInTheChrome() {
        val groups = mutableListOf<CarouselCategory>()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    ToolRail(
                        vm = noopActions,
                        uiState = EditorUiState(),
                        lit = emptySet(),
                        secondary = emptySet(),
                        callbacks = ToolRailCallbacks(
                            onToolOptions = {}, onToolGroup = { groups += it }, onBrushMenu = {},
                        ),
                    )
                }
            }
        }
        listOf("Shortcuts", "Favourites", "Recent", "Most used").forEach { label ->
            assertTrue(label, rule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            assertTrue(
                label,
                rule.onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            )
            assertTrue(
                label,
                rule.onAllNodesWithTag("shortcuts.tab.$label", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            )
        }
        // The brush library opens from its rail buttons and nowhere else.
        assertTrue(groups.isEmpty())
        rule.onNodeWithTag("rail.$RETOUCH_RAIL_ID").performClick()
        assertEquals(listOf(CarouselCategory.EFFECTS), groups)
    }

    private val noopActions = object : ToolRailActions {
        override fun setActiveTool(tool: Tool) = Unit
        override fun onColorClicked() = Unit
        override fun resetViewport() = Unit
        override fun onUndoClicked() = Unit
        override fun onRedoClicked() = Unit
        override fun setBrushSize(size: Float) = Unit
        override fun setBrushFeathering(feathering: Float) = Unit
    }
}
