package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The logo menu as a screen reader meets it: the button says whether its menu is showing, the opened
 * panel is announced as a pane, and an on/off row is a checkbox with a state, not a button that
 * reads its drawn dot out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class LogoMenuSemanticsUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var open by mutableStateOf(false)
    private var layersShown by mutableStateOf(true)
    private val toggles = mutableListOf<Boolean>()

    private fun compose() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LogoMenu(
                    open = open,
                    onOpenChange = { open = it },
                    rows = buildMenu {
                        toggle("Layers", layersShown) { toggles += it; layersShown = it }
                        listItem("Settings") {}
                    },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun logo() = rule.onNodeWithTag("logo.menu").fetchSemanticsNode().config

    @Test
    fun `the button reports whether its menu is open`() {
        compose()
        assertEquals(listOf("Graffux menu"), logo().getOrNull(SemanticsProperties.ContentDescription))
        assertEquals("Closed", logo().getOrNull(SemanticsProperties.StateDescription))
        rule.onNodeWithTag("logo.menu").performClick()
        rule.waitForIdle()
        assertTrue(open)
        assertEquals("Open", logo().getOrNull(SemanticsProperties.StateDescription))
    }

    @Test
    fun `the opened panel is announced as a pane and read as a group`() {
        compose()
        rule.onNodeWithTag("logo.menu").performClick()
        rule.waitForIdle()
        val panel = rule.onNodeWithTag("chrome.menu").fetchSemanticsNode().config
        assertEquals("Menu", panel.getOrNull(SemanticsProperties.PaneTitle)) // distinct from the button's name
        assertEquals(true, panel.getOrNull(SemanticsProperties.IsTraversalGroup))
    }

    @Test
    fun `an on-off row is a checkbox named by its visible text, with its state and no dot`() {
        compose()
        rule.onNodeWithTag("logo.menu").performClick()
        rule.waitForIdle()
        // The name is what is drawn ("Layers: shown"), so what a voice-control user sees is what they can say.
        val row = rule.onNode(hasContentDescription("Layers: shown")).fetchSemanticsNode().config
        assertEquals(Role.Checkbox, row.getOrNull(SemanticsProperties.Role))
        assertEquals(ToggleableState.On, row.getOrNull(SemanticsProperties.ToggleableState))
        // The row carries a name and a state and no text of its own: the dot is not among what is read. (The
        // dot's own node is cleared from the tree, so this, not a search for it, is what shows it is not read.)
        assertEquals(null, row.getOrNull(SemanticsProperties.Text))
        assertEquals(listOf("Layers: shown"), row.getOrNull(SemanticsProperties.ContentDescription))
        rule.onNode(hasContentDescription("Layers: shown")).performClick()
        rule.waitForIdle()
        assertEquals(listOf(false), toggles)
        assertTrue("an on-off row keeps the menu open", open)
        val after = rule.onNode(hasContentDescription("Layers: hidden")).fetchSemanticsNode().config
        assertEquals(ToggleableState.Off, after.getOrNull(SemanticsProperties.ToggleableState))
    }
}
