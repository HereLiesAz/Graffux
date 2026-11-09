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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.graffitixr.feature.editor.PaletteMixerDialog
import com.hereliesaz.graffitixr.feature.editor.newMixerBoard
import com.hereliesaz.graffitixr.feature.editor.LongOperationOverlay
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the surfaces the UI/UX audit fixes changed. Set AUDIT_SCREENSHOTS to a directory to save
 * the renders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class AuditUiShotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        val dir = System.getenv("AUDIT_SCREENSHOTS") ?: return
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun dp(px: Float) = Math.round(px / rule.density.density)

    @Test
    fun `the layer menu is sectioned, with destructive rows last and 48dp rows`() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0F))) {
                    MenuPanel(
                        rows = buildMenu {
                            section("Layer")
                            listItem("Adjust") {}
                            listItem("Blend mode") {}
                            section("Arrange")
                            listItem("Move up") {}
                            listItem("Move down") {}
                            section("Properties")
                            check("Lock transparency", true) {}
                            check("Clip to layer below", false) {}
                            section("Layout")
                            check("Horizontal: Left & right", true) {}
                            section("Remove")
                            destructiveItem("Clear layer") {}
                            destructiveItem("Delete") {}
                        },
                        onDismiss = {},
                        title = "Fill",
                        maxHeight = 800.dp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        save("layer-menu")
        assertTrue(dp(rule.onNodeWithText("Move up").fetchSemanticsNode().boundsInRoot.height) >= 48)
        rule.onNodeWithText("Lock transparency").assertIsDisplayed()
    }

    @Test
    fun `the progress overlay is labelled and cancellable`() {
        var cancelled = false
        rule.setContent {
            GraffitiXRTheme {
                LongOperationOverlay(label = "Making poster PDF…", onCancel = { cancelled = true })
            }
        }
        rule.waitForIdle()
        save("progress")
        rule.onNodeWithText("Making poster PDF…").assertIsDisplayed()
        rule.onNodeWithTag("editor.progress.cancel").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun `the paint mixer names its tubes and offers a labelled digital hex match`() {
        rule.setContent {
            GraffitiXRTheme {
                PaletteMixerDialog(
                    board = newMixerBoard(),
                    currentColor = Color(0xFFFF3EA5),
                    secondaryColor = Color.Black,
                    onSelectColor = {},
                    onSelectSecondaryColor = {},
                    onSwapColors = {},
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        save("paint-mixer")
        rule.onNodeWithContentDescription("Cadmium Red, opaque, strong tinter").assertIsDisplayed()
        rule.onNodeWithContentDescription("Foreground brush").assertIsDisplayed()
        rule.onNodeWithTag("mixer.board").assertIsDisplayed()
        rule.onNodeWithTag("mixer.hex").assertIsDisplayed()
        rule.onNodeWithText("Digital: match a colour").assertIsDisplayed()
    }

    @Test
    fun `a message's OK is a 48dp target`() {
        val controller = MessageController().apply { show(message = "Saved poster.pdf to Downloads", actionLabel = "Open", onAction = {}) }
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MessageHost(controller, okLabel = "OK")
            }
        }
        rule.waitForIdle()
        val ok = rule.onNodeWithTag("chrome.message.ok").fetchSemanticsNode().boundsInRoot
        assertTrue(dp(ok.height) >= 48 && dp(ok.width) >= 48)
        rule.onNodeWithTag("chrome.message.action").assertIsDisplayed()
    }

    @Test
    fun `undo and redo stay on screen in landscape`() {
        val layers = listOf(Layer(id = "a", name = "A"))
        val state = EditorUiState(undoCount = 1, layers = layers, activeLayerId = "a", brushSize = 24f, activeColor = Color(0xFFFF3EA5), activeTool = Tool.NONE)
        val actions = object : ToolRailActions {
            override fun setActiveTool(tool: Tool) = Unit
            override fun onColorClicked() = Unit
            override fun resetViewport() = Unit
            override fun onUndoClicked() = Unit
            override fun onRedoClicked() = Unit
            override fun setBrushSize(size: Float) = Unit
            override fun setBrushFeathering(feathering: Float) = Unit
        }
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // A landscape phone's height: 360dp less the bars.
                Box(Modifier.fillMaxSize().padding(bottom = 844.dp - 320.dp).background(Color(0xFF0E0E0F))) {
                    ToolRail(
                        vm = actions, uiState = state, lit = emptySet(), secondary = emptySet(),
                        callbacks = ToolRailCallbacks({}, {}, {}),
                        modifier = Modifier.padding(start = RailSideInset, top = RailTopInset),
                    )
                }
            }
        }
        rule.waitForIdle()
        save("rail-landscape")
        val redo = rule.onNodeWithTag("rail.history.redo").fetchSemanticsNode().boundsInRoot
        assertTrue("redo bottom ${dp(redo.bottom)}", dp(redo.bottom) <= 320)
    }

    @Test
    fun `squeezing a tube and dipping the brush puts that paint in the brush hand`() {
        val board = newMixerBoard()
        var applied: Color? = null
        rule.setContent {
            GraffitiXRTheme {
                PaletteMixerDialog(
                    board = board,
                    currentColor = Color.White,
                    secondaryColor = Color.Black,
                    onSelectColor = { applied = it },
                    onSelectSecondaryColor = {},
                    onSwapColors = {},
                    onDismiss = {},
                )
            }
        }
        // Paint the board and load the brush the way a touch would, then confirm what the user is told.
        board.squeeze(4, 30f, 30f, 7f, 0.9f)
        board.dip(30f, 30f)
        rule.waitForIdle()
        assertTrue(board.brushRecipe().tubes.first().first.id == "cadmium-red")
    }
}
