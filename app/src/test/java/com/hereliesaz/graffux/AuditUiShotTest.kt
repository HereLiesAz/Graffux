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
import com.hereliesaz.graffitixr.feature.editor.ColorPickerDialog
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
    fun `the colour picker names its swatches and offers hex and opacity`() {
        rule.setContent {
            GraffitiXRTheme {
                ColorPickerDialog(
                    currentColor = Color(0xFFFF3EA5),
                    secondaryColor = Color.Black,
                    history = listOf(Color(0xFF00FFFF), Color(0xFFFFB300)),
                    onSelectColor = {},
                    onDismiss = {},
                    strings = rememberAppStrings(),
                )
            }
        }
        rule.waitForIdle()
        save("colour-picker")
        rule.onNodeWithContentDescription("Foreground colour #FF3EA5").assertIsDisplayed()
        rule.onNodeWithContentDescription("Background colour #000000").assertIsDisplayed()
        rule.onNodeWithTag("color.hex").assertIsDisplayed()
        rule.onNodeWithTag("color.alpha").assertIsDisplayed()
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
    fun `closing the picker on the background target reports the background colour`() {
        var closedOn: Color? = null
        rule.setContent {
            GraffitiXRTheme {
                ColorPickerDialog(
                    currentColor = Color(0xFFFF3EA5),
                    secondaryColor = Color(0xFF00FFFF),
                    history = emptyList(),
                    onSelectColor = {},
                    onDismiss = {},
                    strings = rememberAppStrings(),
                    onDismissWithColor = { closedOn = it },
                )
            }
        }
        rule.onNodeWithTag("color.target.background").performClick()
        rule.onNodeWithContentDescription("Close Colour").performClick()
        rule.waitForIdle()
        assertTrue("closed on $closedOn", closedOn?.toArgb() == Color(0xFF00FFFF).toArgb())
    }
}
