package com.hereliesaz.graffitixr.feature.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The mandatory project dialog: no way to dismiss it, and its two actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ProjectGateDialogTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `tapping outside does not dismiss, and it installs no back handler`() {
        rule.setContent {
            ProjectGateDialog(state = ProjectGateState("Untitled 1"), onLoad = {}, onSave = {})
        }
        rule.onNodeWithTag(TAG_SCRIM).performTouchInput { click(topLeft) }
        rule.onNodeWithTag(TAG_SCRIM).performTouchInput { click(bottomRight) }
        rule.onNodeWithTag(TAG_DIALOG).assertIsDisplayed()
        // No cancel affordance at all.
        assertTrue(rule.onAllNodesWithTextCount("Cancel") == 0)
        // Back is not intercepted: the dispatcher falls through to the activity's own root behaviour.
        assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        rule.onNodeWithTag(TAG_DIALOG).assertIsDisplayed()
    }

    @Test
    fun `save hands over the typed, sanitised name`() {
        var saved: String? = null
        rule.setContent {
            ProjectGateDialog(state = ProjectGateState("Untitled 3"), onLoad = {}, onSave = { saved = it })
        }
        rule.onNodeWithTag(TAG_NAME).performTextReplacement("Wall piece")
        rule.onNodeWithTag(TAG_SAVE).performClick()
        assertEquals("Wall piece", saved)
    }

    @Test
    fun `load opens the picker and the dialog stays when it comes back empty`() {
        var loads = 0
        rule.setContent {
            ProjectGateDialog(state = ProjectGateState("Untitled 1"), onLoad = { loads += 1 }, onSave = {})
        }
        rule.onNodeWithTag(TAG_LOAD).performClick()
        assertEquals(1, loads)
        // The picker returned nothing: the host keeps showing the same state, so the dialog is there.
        rule.onNodeWithTag(TAG_DIALOG).assertIsDisplayed()
        rule.onNodeWithTag(TAG_LOAD).assertIsDisplayed()
    }

    @Test
    fun `busy state replaces the actions`() {
        rule.setContent {
            ProjectGateDialog(state = ProjectGateState("Untitled 1", busyLabel = "Saving…"), onLoad = {}, onSave = {})
        }
        rule.onNodeWithText("Saving…").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTagCount(TAG_SAVE) == 0)
        assertTrue(rule.onAllNodesWithTagCount(TAG_LOAD) == 0)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
