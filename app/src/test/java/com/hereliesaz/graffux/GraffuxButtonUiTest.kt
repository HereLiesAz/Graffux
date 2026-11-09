package com.hereliesaz.graffux

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
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
 * Buttons at the largest font scale. A full-width button had a fixed 32dp height, which cut its
 * label off from a font scale of about 1.3 up, and a confirm dialog's message was clipped, not
 * scrolled, so a long message or a large font hid text with no way to reach it.
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
        rule.onNodeWithText("Delete").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
    }
}
