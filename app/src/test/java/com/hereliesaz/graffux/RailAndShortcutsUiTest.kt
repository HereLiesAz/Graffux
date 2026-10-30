package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.ToolUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The rail reaches the brush and the retouch tools; the shortcuts sheet's tabs lay out and switch. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class RailAndShortcutsUiTest {

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

    @Test
    fun shortcutTabsSitSideBySideAndSwitch() {
        val usage = ToolUsage(counts = mapOf("HEAL" to 2), lastUsedMs = mapOf("HEAL" to 10L))
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(411.dp)) {
                    ShortcutsSheet(
                        activeTool = Tool.NONE, usage = usage, favorites = emptyList(),
                        onPickTool = {}, onToggleFavorite = {},
                    )
                }
            }
        }
        val fav = rule.onNodeWithTag("shortcuts.tab.Favourites").getUnclippedBoundsInRoot()
        val recent = rule.onNodeWithTag("shortcuts.tab.Recent").getUnclippedBoundsInRoot()
        val most = rule.onNodeWithTag("shortcuts.tab.Most used").getUnclippedBoundsInRoot()
        // One row, left to right, none squeezed into a letter-per-line column.
        assertEquals(fav.top, recent.top)
        assertEquals(fav.top, most.top)
        assertTrue(fav.right <= recent.left && recent.right <= most.left)
        assertTrue(fav.bottom - fav.top < 80.dp)
        assertTrue(fav.right - fav.left < 200.dp)
        // No favourites yet: opens on Recent, which has the tool used.
        rule.onNodeWithText("Heal").assertIsDisplayed()
        rule.onNodeWithTag("shortcuts.tab.Favourites").performClick()
        rule.onNodeWithText("Hold a tool to pin it here.").assertIsDisplayed()
    }
}
