package com.hereliesaz.graffux

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.data.brush.CustomBrush
import com.hereliesaz.graffitixr.design.components.FullWidthButtonHeight
import com.hereliesaz.graffitixr.design.components.GraffuxButton
import com.hereliesaz.graffitixr.feature.editor.AddContentDialog
import com.hereliesaz.graffitixr.feature.editor.CustomBrushesWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.design.components.ConfirmDialog
import com.hereliesaz.graffitixr.feature.editor.OpenProjectWindow
import com.hereliesaz.graffitixr.feature.editor.OpenScreenState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Buttons at large font scales. A full-width button had a fixed 32dp height; the label's line is 16sp
 * (labelLarge) plus 12dp of padding, so it needs more than 32dp once the font scale passes 1.25 (checked with
 * the theme by the two probes below: no overflow at 1.0, overflow at 1.5), which is the "about 1.3" the fix
 * is for. A confirm dialog's message was clipped, not scrolled, so a long message or a large font hid text
 * with no way to reach it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h600dp-xxhdpi", application = Application::class)
class GraffuxButtonUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun AtFontScale(scale: Float, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        MaterialTheme(colorScheme = darkColorScheme()) {
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = scale)) { content() }
        }
    }

    /** The label's own layout: not cut by its height and not ellipsized. */
    private fun assertLabelNotCut(label: String) {
        val node = rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val layout = results.single()
        val ellipsized = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
        assertTrue("\"$label\" overflows its height (${layout.size})", !layout.didOverflowHeight)
        assertTrue("\"$label\" is cut to an ellipsis", !ellipsized)
    }

    @Test
    fun `the Open window's full-width buttons hold their label at 200 percent font`() {
        rule.setContent {
            AtFontScale(2f) {
                OpenProjectWindow(
                    state = OpenScreenState(inApp = listOf(GraffitiProject(id = "a", name = "Wall"))),
                    currentProjectId = null,
                    onOpenProject = {}, onOpenFile = { _: Uri -> }, onNew = {}, onChooseLocation = {},
                    onDelete = {}, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        assertLabelNotCut("+ New project")
        assertLabelNotCut("Choose location…")
    }

    @Test
    fun `a confirm dialog's message scrolls so a long one or a large font is never cut off`() {
        rule.setContent {
            AtFontScale(2f) {
                ConfirmDialog(
                    title = "Delete project?",
                    message = "\"Wall\" and everything in it will be deleted permanently. ".repeat(12),
                    confirmLabel = "Delete",
                    onConfirm = {}, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        val message = rule.onNode(hasText("everything in it", substring = true), useUnmergedTree = true)
            .fetchSemanticsNode()
        assertNotNull("the message must be scrollable", message.config.getOrNull(SemanticsActions.ScrollBy))
        // Both buttons are on screen, not pushed out of the dialog by the long message at 200 percent font.
        rule.onNodeWithText("Delete").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    private fun layoutOf(label: String): TextLayoutResult {
        val node = rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    @Test
    fun `a label wraps to two lines in a narrow button and a longer one is cut at two with an ellipsis`() {
        rule.setContent {
            AtFontScale(1f) {
                androidx.compose.foundation.layout.Column {
                    GraffuxButton("Delete project", {}, Modifier.width(100.dp))
                    GraffuxButton("Choose a new location for this project please", {}, Modifier.width(100.dp))
                }
            }
        }
        rule.waitForIdle()
        val two = layoutOf("Delete project")
        assertEquals(2, two.lineCount)
        assertTrue("two lines hold it whole", (0 until two.lineCount).none { two.isLineEllipsized(it) })
        val cut = layoutOf("Choose a new location for this project please")
        assertEquals("never more than two lines", 2, cut.lineCount)
        assertTrue("the second line ends in an ellipsis", cut.isLineEllipsized(1))
    }

    private fun overflowsAt(scale: Float): Boolean {
        rule.setContent {
            AtFontScale(scale) { GraffuxButton("Choose location", {}, Modifier.height(FullWidthButtonHeight)) }
        }
        rule.waitForIdle()
        return layoutOf("Choose location").didOverflowHeight
    }

    @Test
    fun `probe - a fixed 32dp height holds the label at font scale 1_0`() {
        assertFalse("the threshold is above 1.0", overflowsAt(1f))
    }

    @Test
    fun `probe - a fixed 32dp height cuts the label at font scale 1_5`() {
        assertTrue("and below 1.5", overflowsAt(1.5f))
    }

    private fun heightOf(label: String) =
        rule.onNode(hasClickAction() and hasText(label)).fetchSemanticsNode().size.height

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h700dp-xxhdpi", application = Application::class)
    fun `the Add window's 3-up rows stay equal in height when a label wraps`() {
        rule.setContent {
            AtFontScale(2f) {
                AddContentDialog({}, {}, {}, {}, {}, {}, {}, onDismiss = {})
            }
        }
        rule.waitForIdle()
        listOf(listOf("Text", "Rectangle", "Ellipse"), listOf("Line", "Triangle", "Pentagon")).forEach { row ->
            val heights = row.map { heightOf(it) }
            assertEquals("row $row heights $heights", 1, heights.toSet().size)
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h700dp-xxhdpi", application = Application::class)
    fun `a custom brush card's Edit and Delete stay equal in height and keep their labels`() {
        val brush = CustomBrush("x", BuiltInBrushes.presets.first())
        rule.setContent {
            AtFontScale(2f) {
                CustomBrushesWindow(
                    brushes = listOf(brush),
                    activeBrushName = null,
                    brushColor = androidx.compose.ui.graphics.Color.White,
                    onSelect = {}, onEdit = {}, onDelete = {}, onNew = {}, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        assertEquals(heightOf("Edit"), heightOf("Delete"))
        assertTrue(!layoutOf("Edit").didOverflowHeight && !layoutOf("Delete").didOverflowHeight)
    }
}
