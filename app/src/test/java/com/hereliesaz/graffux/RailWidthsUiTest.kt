package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `LocalRailInset` is built from [ToolRailWidth] and [LayersRailWidth]; a sheet between the rails clears each by
 * that. This measures the two rails as drawn, so the constants cannot drift from what is on the screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class RailWidthsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val actions = object : ToolRailActions {
        override fun setActiveTool(tool: Tool) = Unit
        override fun onColorClicked() = Unit
        override fun resetViewport() = Unit
        override fun onUndoClicked() = Unit
        override fun onRedoClicked() = Unit
        override fun setBrushSize(size: Float) = Unit
        override fun setBrushFeathering(feathering: Float) = Unit
    }

    @Test
    fun `the tool rail and the layers rail are as wide as the widths the rail inset is built from`() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Row {
                    ToolRail(
                        vm = actions,
                        uiState = EditorUiState(),
                        lit = emptySet(),
                        secondary = emptySet(),
                        callbacks = ToolRailCallbacks(
                            onToolOptions = {},
                            onToolGroup = {},
                            onBrushMenu = {},
                            onBrush = {},
                            onPackWheelToggle = {},
                        ),
                    )
                    LayersRail(
                        layers = listOf(Layer("a", "a")),
                        activeLayerId = "a",
                        secondary = emptySet(),
                        expansion = emptyMap(),
                        callbacks = LayersRailCallbacks({}, {}, { _, _ -> }, { _, _ -> }, {}),
                    )
                }
            }
        }
        rule.waitForIdle()
        val density = rule.density.density
        val tools = rule.onNodeWithTag("rail.tools.pill", useUnmergedTree = true).fetchSemanticsNode().size.width
        val layers = rule.onNodeWithTag("rail.layers", useUnmergedTree = true).fetchSemanticsNode().size.width
        assertEquals(ToolRailWidth.value * density, tools.toFloat(), 1f)
        assertEquals(LayersRailWidth.value * density, layers.toFloat(), 1f)
    }
}
