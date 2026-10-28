@file:Suppress("MaxLineLength")

package com.hereliesaz.graffux

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.StrokeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The chrome as MainActivity composes it, without the editor behind it: the rails' clicks reach the
 * editor, a touch on the canvas hides everything at once and shuts what was open, and a layer
 * dragged in the layers rail reports its new place. Set CHROME_SCREENSHOTS to a directory to save
 * a render of each state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ChromeUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()
    private val actions = object : ToolRailActions {
        override fun setActiveTool(tool: Tool) { calls += "tool:$tool" }
        override fun onColorClicked() { calls += "color" }
        override fun resetViewport() { calls += "fit" }
        override fun onUndoClicked() { calls += "undo" }
        override fun onRedoClicked() { calls += "redo" }
        override fun setBrushSize(size: Float) { calls += "size" }
        override fun setBrushFeathering(feathering: Float) { calls += "feather" }
    }

    private val layers = listOf(
        Layer(id = "a", name = "Bottom"),
        Layer(id = "b", name = "Middle"),
        Layer(id = "c", name = "Top"),
    )
    private var relocated: Pair<List<String>, String?>? = null
    private var menuFor: String? = null
    private var sheetOpen by mutableStateOf(true)
    private var logoOpen by mutableStateOf(true)
    private val gate = StrokeGate()

    private fun compose(uiState: EditorUiState = EditorUiState(undoCount = 1, layers = layers, activeLayerId = "b")) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val touch = remember { CanvasTouch() }
                val chrome = rememberChromeVisibility(touch, gate) {
                    logoOpen = false
                    sheetOpen = false
                }
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .observeCanvasTouch(touch)
                            .fillMaxSize()
                            .background(Color(0xFF2B2B30))
                            .testTag("canvas"),
                    )
                    ShownWhenNotDrawing(chrome) {
                        ToolRail(
                            vm = actions,
                            uiState = uiState,
                            lit = activeRailClassifiers(RailClassifierInputs(uiState, emptyList(), emptyList(), false)),
                            secondary = emptySet(),
                            callbacks = ToolRailCallbacks({ calls += "options" }, { calls += "group:$it" }, { calls += "brushMenu" }),
                            modifier = Modifier.align(Alignment.TopStart).padding(start = RailSideInset, top = RailTopInset),
                        )
                        LayersRail(
                            layers = uiState.layers,
                            activeLayerId = uiState.activeLayerId,
                            secondary = emptySet(),
                            expansion = emptyMap(),
                            callbacks = LayersRailCallbacks(
                                onActivate = { calls += "activate:$it" },
                                onOpenMenu = { menuFor = it },
                                onExpand = { _, _ -> },
                                onRelocate = { order, parent -> relocated = order to parent },
                                onAdd = { calls += "add" },
                            ),
                            modifier = Modifier.align(Alignment.TopEnd).padding(end = RailSideInset, top = LayersRailTopInset),
                        )
                        LogoMenu(
                            open = logoOpen,
                            onOpenChange = { logoOpen = it },
                            rows = buildMenu { listItem("Settings") {} },
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                    CarouselSheet(
                        open = sheetOpen,
                        onOpenChange = { sheetOpen = it },
                        hiddenForDrawing = { chrome.hidden },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) { Text("sheet content", Modifier.testTag("sheet.content")) }
                }
            }
        }
        rule.waitForIdle()
    }

    /** One frame, with the state written outside composition applied first, as a real frame would. */
    private fun nextFrame() {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
        rule.onRoot().fetchSemanticsNode()
    }

    /** Steps the clock frame by frame (syncing the tree each one), as real frames would run. */
    private fun frames(ms: Long) {
        val end = rule.mainClock.currentTime + ms
        while (rule.mainClock.currentTime < end) {
            nextFrame()
        }
    }

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun screenshot(name: String) {
        val dir = System.getenv("CHROME_SCREENSHOTS") ?: return
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `the rails render and their buttons reach the editor`() {
        // The open menu drops over the layers rail (as in the mockup), so start with it shut.
        logoOpen = false
        compose()
        screenshot("1-rails")
        assertTrue(shown("rail.tools"))
        assertTrue(shown("rail.layers"))
        rule.onNodeWithTag("rail.tool.eraser").performClick()
        rule.onNodeWithTag("rail.tool.color").performClick()
        rule.onNodeWithTag("rail.history.undo").performClick()
        rule.onNodeWithTag("rail.grp.vector").performClick()
        rule.onNodeWithTag("rail.layer.add").performClick()
        rule.onNodeWithTag("rail.layer.c").performClick()
        rule.onNodeWithTag("rail.layer.b").performClick()
        rule.waitForIdle()
        assertEquals(
            listOf("tool:ERASER", "color", "undo", "group:VECTOR", "add", "activate:c"),
            calls,
        )
        assertEquals("a second tap on the active layer opens its menu", "b", menuFor)
    }

    @Test
    fun `redo is off with nothing to redo`() {
        compose()
        rule.onNodeWithTag("rail.history.redo").performClick()
        rule.waitForIdle()
        assertFalse("redo" in calls)
    }

    @Test
    fun `a canvas touch hides every piece of chrome at once and shuts what was open`() {
        compose()
        assertTrue(shown("chrome.menu"))
        assertTrue(sheetOpen)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("canvas").performTouchInput { down(center) }
        nextFrame()
        screenshot("2-touching")
        assertFalse("tool rail", shown("rail.tools"))
        assertFalse("layers rail", shown("rail.layers"))
        assertFalse("logo menu", shown("logo.menu"))
        nextFrame()
        assertFalse("the sheet shuts, not just hides", sheetOpen)
        rule.onNodeWithTag("canvas").performTouchInput { up() }
        frames(DRAWING_UI_RETURN_DELAY_MS / 2)
        assertFalse("still held after the finger lifts", shown("rail.tools"))
        frames(DRAWING_UI_RETURN_DELAY_MS)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        screenshot("3-back")
        assertTrue("rails come back", shown("rail.tools") && shown("rail.layers") && shown("logo.menu"))
        assertFalse("the menu stays closed", shown("chrome.menu"))
        assertFalse("the sheet stays shut", sheetOpen)
    }

    @Test
    fun `a stroke hides the chrome too`() {
        compose()
        rule.mainClock.autoAdvance = false
        gate.strokeActive = true
        nextFrame()
        assertFalse(shown("rail.tools"))
        assertFalse(sheetOpen)
        gate.strokeActive = false
        frames(DRAWING_UI_RETURN_DELAY_MS * 2)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(shown("rail.tools"))
    }

    @Test
    fun `dragging a layer reorders it among its siblings`() {
        compose()
        // Top-first on screen: c, b, a. Drag the top one (c) below the bottom one (a).
        rule.onNodeWithTag("rail.layer.c").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, 40f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("layer.b", "layer.a", "layer.c") to null, relocated)
        assertEquals("a drag opens no menu", null, menuFor)
    }

    @Test
    fun `a long-press without a drag opens the layer's menu`() {
        compose()
        rule.onNodeWithTag("rail.layer.a").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        rule.waitForIdle()
        assertEquals("a", menuFor)
        assertEquals(null, relocated)
    }
}
