@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffux

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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
import java.io.File


/**
 * The rails in the approved mockup's "Phone · sheet shut" state (390 × 844, a 24px brush, pink, four
 * layers with the third active and the second hidden), checked against the mockup's measurements.
 * Set RAIL_SCREENSHOTS to a directory to save the render for a side-by-side with the mockup.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class RailMockupShotTest {

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

    private fun solid(argb: Int): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    private fun compose(menuOpen: Boolean) {
        // Bottom-first, as the editor keeps them; the rail shows them top-first.
        val layers = listOf(
            Layer(id = "back", name = "Back", bitmap = solid(0xFF3A3A3F.toInt())),
            Layer(id = "fill", name = "Fill", bitmap = solid(0xFFFF3EA5.toInt())),
            Layer(id = "outline", name = "Outline", isVisible = false),
            Layer(id = "shine", name = "Shine", bitmap = solid(0xFFEDEDED.toInt())),
        )
        val state = EditorUiState(undoCount = 1, layers = layers, activeLayerId = "fill", brushSize = 24f, activeColor = Color(0xFFFF3EA5))
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0F))) {
                    ToolRail(
                        vm = actions,
                        uiState = state,
                        lit = emptySet(),
                        secondary = emptySet(),
                        callbacks = ToolRailCallbacks({}, {}, {}),
                        modifier = Modifier.align(Alignment.TopStart).padding(start = RailSideInset, top = RailTopInset),
                    )
                    LayersRail(
                        layers = layers,
                        activeLayerId = "fill",
                        secondary = emptySet(),
                        expansion = emptyMap(),
                        callbacks = LayersRailCallbacks({}, {}, { _, _ -> }, { _, _ -> }, {}),
                        historyActions = actions, undoCount = state.undoCount, redoCount = state.redoCount,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = RailSideInset, top = LayersRailTopInset),
                    )
                    LogoMenu(open = menuOpen, onOpenChange = {}, rows = buildMenu { listItem("Settings") {} }, modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
        rule.waitForIdle()
    }

    private fun save(name: String) {
        val dir = System.getenv("RAIL_SCREENSHOTS") ?: return
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `the rails and history fit their requested positions`() {
        compose(menuOpen = false)
        save("app-Main")
        val tools = rule.onNodeWithTag("rail.tools").fetchSemanticsNode().boundsInRoot
        val layers = rule.onNodeWithTag("rail.layers").fetchSemanticsNode().boundsInRoot
        val logo = rule.onNodeWithTag("logo.menu").fetchSemanticsNode().boundsInRoot
        val d = rule.density.density
        fun dp(px: Float) = Math.round(px / d)
        // The unified Shapes/Vector group replaces the separate Shape button; the tools rail
        // still starts at the mockup's inset and stays within the visible workspace.
        assertEquals(listOf(12, 16, 52), listOf(dp(tools.left), dp(tools.top), dp(tools.width)))
        org.junit.Assert.assertTrue("tools rail remains within the 844dp viewport", dp(tools.bottom) <= 844)
        // Undo and Redo now belong below the Layers rail, outside its scrolling content.
        val history = rule.onNodeWithTag("rail.history").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("history follows layers", history.top >= layers.bottom)
        rule.onNodeWithTag("rail.history.undo").assertExists()
        rule.onNodeWithTag("rail.history.redo").assertExists()
        // 2 + 12 + 5 × 44 + 5 gaps × 6 + 9, exactly the mockup's, plus the trash at its foot (a
        // divider and one 48dp target, with their gaps): 65.
        assertEquals(listOf(390 - 12 - 52, 72, 52, 273 + 65), listOf(dp(layers.left), dp(layers.top), dp(layers.width), dp(layers.height)))
        // The 48dp target is 2dp outside the drawn 44dp button on every side.
        assertEquals(listOf(390 - 18 - 44, 16, 44), listOf(dp(logo.left) + 2, dp(logo.top) + 2, dp(logo.width) - 4))
    }

    @Test
    fun `the open logo`() {
        compose(menuOpen = true)
        save("app-Menu")
        val logo = rule.onNodeWithTag("logo.menu").fetchSemanticsNode().boundsInRoot
        assertEquals(390 - 12 - 44, Math.round(logo.left / rule.density.density) + 2)
    }
}
