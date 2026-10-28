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
    fun `the rails sit and measure where the mockup has them`() {
        compose(menuOpen = false)
        save("app-Main")
        val tools = rule.onNodeWithTag("rail.tools").fetchSemanticsNode().boundsInRoot
        val layers = rule.onNodeWithTag("rail.layers").fetchSemanticsNode().boundsInRoot
        val logo = rule.onNodeWithTag("logo.menu").fetchSemanticsNode().boundsInRoot
        val d = rule.density.density
        fun dp(px: Float) = Math.round(px / d)
        // The mockup's 488 (2 border + 12 padding + 9 × 44 + 10 gaps × 6 + 2 separators × 9), plus the
        // Eraser the app keeps (44 + 6).
        assertEquals(listOf(12, 16, 56, 488 + 50), listOf(dp(tools.left), dp(tools.top), dp(tools.width), dp(tools.height)))
        // 2 + 12 + 5 × 44 + 5 gaps × 6 + 9, exactly the mockup's.
        assertEquals(listOf(390 - 12 - 56, 72, 56, 273), listOf(dp(layers.left), dp(layers.top), dp(layers.width), dp(layers.height)))
        assertEquals(listOf(390 - 18 - 44, 16, 44), listOf(dp(logo.left), dp(logo.top), dp(logo.width)))
    }

    @Test
    fun `the open logo`() {
        compose(menuOpen = true)
        save("app-Menu")
        val logo = rule.onNodeWithTag("logo.menu").fetchSemanticsNode().boundsInRoot
        assertEquals(390 - 12 - 44, Math.round(logo.left / rule.density.density))
    }
}
