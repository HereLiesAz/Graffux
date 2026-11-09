package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings' frame: the screen is announced as a pane with a heading, its close button is a 48dp
 * target, and the content keeps clear of the system bars and cutout (the full-bleed screen had
 * none of that: its title and close button sat under the status bar or a landscape cutout).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class SettingsFrameUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var closed = 0

    private fun compose() {
        WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false)
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SettingsFrame(onClose = { closed++ }) { Text("body") }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the page is a pane named Settings and its title is a heading`() {
        compose()
        rule.onNodeWithTag("settings.title").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val panes = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().let { root ->
            generateSequence(listOf(root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }
                .flatten().mapNotNull { it.config.getOrNull(SemanticsProperties.PaneTitle) }.toList()
        }
        assertEquals(listOf("Settings"), panes)
    }

    @Test
    fun `close is a 48dp target that closes`() {
        compose()
        val close = rule.onNodeWithTag("settings.close")
        close.assert(hasContentDescription("Close settings"))
        // The touch bounds, not the drawn size: a 40dp IconButton is padded out to the 48dp minimum.
        val bounds = close.fetchSemanticsNode().touchBoundsInRoot
        val min = 48 * rule.density.density
        assertTrue(
            "close touch target is ${bounds.width}x${bounds.height}px",
            bounds.width >= min && bounds.height >= min,
        )
        close.performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `the title clears the status bar inset`() {
        compose()
        val before = rule.onNodeWithTag("settings.title").fetchSemanticsNode().boundsInRoot.top
        rule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 120, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, insets)
        }
        rule.waitForIdle()
        val after = rule.onNodeWithTag("settings.title").fetchSemanticsNode().boundsInRoot.top
        assertTrue("title top $before -> $after px; a 120px status bar must push it down", after >= before + 120f)
    }
}
