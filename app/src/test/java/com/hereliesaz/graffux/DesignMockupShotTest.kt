@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffux

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.FrameOps
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Rect
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The design tools' chrome in the approved mockup's "Design tools" boards: the phone's Properties
 * board (the Frame tab in the sheet) and the "Tablet · properties side panel" board, checked
 * against the mockup's measurements. The canvas itself (the frames, drawn by the editor's GPU
 * path) is not composed here. Set DESIGN_SCREENSHOTS to a directory to save the renders for a
 * side-by-side with the mockup.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignMockupShotTest {

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

    private val design = object : DesignActions {
        override fun setBox(x: Float, y: Float, width: Float, height: Float) = Unit
        override fun setRotation(degrees: Float) = Unit
        override fun setOpacityPercent(percent: Float) = Unit
        override fun setRadius(radius: Float) = Unit
        override fun setClip(clips: Boolean) = Unit
        override fun setFill(argb: Long?) = Unit
        override fun fillWithColorInHand() = Unit
        override fun addFrame() = Unit
    }

    // Bottom-first, as the editor keeps them: the mockup's Button component, Pay, and the Card in it.
    private val pay = FrameOps.create("pay", "Pay", Rect(390f, 70f, 190f, 340f), fillArgb = 0xFF1A1A1DL)
    private val card = Layer(
        id = "card", name = "Card", parentId = "pay",
        shapes = listOf(VectorShape(ShapeKind.RECTANGLE, width = 218f, height = 70f, cornerRadius = 12f, fillArgb = 0xFF26262AL)),
    )
    private val layers = listOf(Layer(id = "button", name = "Button", componentId = "c"), pay, card)

    private fun save(name: String) {
        val dir = System.getenv("DESIGN_SCREENSHOTS") ?: return
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
    fun `phone properties in the sheet`() {
        val state = EditorUiState(undoCount = 1, layers = layers, activeLayerId = "card", brushSize = 24f, activeColor = Color(0xFFFF3EA5), activeTool = Tool.FRAME)
        val target = designTargetOf(card, Rect(16f, 300f, 218f, 70f))
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0F))) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselSheet(open = true, onOpenChange = {}, bottomInset = EdgeStripHeight + 4.dp) {
                            DesignSheetBody(DesignTab.FRAME, showingControls = true, target = target, actions = design, onSelectTab = {})
                        }
                    }
                    ToolRail(
                        vm = actions, uiState = state, lit = emptySet(), secondary = emptySet(),
                        callbacks = ToolRailCallbacks({}, {}, {}),
                        modifier = Modifier.align(Alignment.TopStart).padding(start = RailSideInset, top = RailTopInset),
                    )
                    LayersRail(
                        layers = layers, activeLayerId = "card", secondary = emptySet(), expansion = mapOf(layerRailId("pay") to true),
                        callbacks = LayersRailCallbacks({}, {}, { _, _ -> }, { _, _ -> }, {}),
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = RailSideInset, top = LayersRailTopInset),
                    )
                    LogoMenu(open = false, onOpenChange = {}, rows = emptyList(), modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
        rule.waitForIdle()
        save("app-DesignInspector")
        val d = rule.density.density
        fun dp(px: Float) = Math.round(px / d)
        // The Frame circle sits after Transform: the rail grows by one 44dp button and a 6dp gap.
        val tools = rule.onNodeWithTag("rail.tools").fetchSemanticsNode().boundsInRoot
        assertEquals(488 + 50 + 50 + 50, dp(tools.height))
        val frame = rule.onNodeWithTag("rail.tool.frame").fetchSemanticsNode().boundsInRoot
        assertEquals(44, dp(frame.width))
        // The four fields across the sheet: 20dp in from each side, 6dp apart.
        val x = rule.onNodeWithTag("design.panel.design.frame").fetchSemanticsNode().boundsInRoot
        assertEquals(390, dp(x.width))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1180dp-h820dp-mdpi", application = Application::class)
    fun `tablet properties side panel`() {
        val state = EditorUiState(undoCount = 1, layers = layers, activeLayerId = "pay", brushSize = 24f, activeColor = Color(0xFFFF3EA5), activeTool = Tool.FRAME)
        val target = designTargetOf(pay, Rect(390f, 70f, 190f, 340f))
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0F))) {
                    ToolRail(
                        vm = actions, uiState = state, lit = emptySet(), secondary = emptySet(),
                        callbacks = ToolRailCallbacks({}, {}, {}),
                        modifier = Modifier.align(Alignment.TopStart).padding(start = RailSideInset, top = RailTopInset),
                    )
                    LayersRail(
                        layers = layers, activeLayerId = "pay", secondary = emptySet(), expansion = emptyMap(),
                        callbacks = LayersRailCallbacks({}, {}, { _, _ -> }, { _, _ -> }, {}),
                        modifier = Modifier.align(Alignment.TopStart).padding(start = RailSideInset + RailWidth + RailSideInset, top = LayersRailTopInset),
                    )
                    PropertiesPanel(
                        target = target, actions = design, placement = PropertiesPlacement.SIDE_PANEL, onPlacement = {},
                        modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight(),
                    )
                    LogoMenu(open = false, onOpenChange = {}, rows = emptyList(), modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
        rule.waitForIdle()
        save("app-DesignTabletPanel")
        val d = rule.density.density
        fun dp(px: Float) = Math.round(px / d)
        val panel = rule.onNodeWithTag("design.sidePanel").fetchSemanticsNode().boundsInRoot
        assertEquals(listOf(1180 - 320, 0, 320, 820), listOf(dp(panel.left), dp(panel.top), dp(panel.width), dp(panel.height)))
        // The layers rail beside the tool rail, at the mockup's x = 80.
        val layersRail = rule.onNodeWithTag("rail.layers").fetchSemanticsNode().boundsInRoot
        assertEquals(80, dp(layersRail.left))
        // The logo keeps its corner, over the panel's header.
        val logo = rule.onNodeWithTag("logo.menu").fetchSemanticsNode().boundsInRoot
        assertEquals(1180 - 18 - 44, dp(logo.left))
    }
}
