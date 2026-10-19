package com.hereliesaz.graffitixr.feature.editor

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
            ProjectGateDialog(state = ProjectGateState("Untitled 1"), onLoad = {}, onSave = { _, _, _ -> })
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
            ProjectGateDialog(state = ProjectGateState("Untitled 3"), onLoad = {}, onSave = { n, _, _ -> saved = n })
        }
        rule.onNodeWithTag(TAG_NAME).performTextReplacement("Wall piece")
        rule.onNodeWithTag(TAG_SAVE).performScrollTo().performClick()
        assertEquals("Wall piece", saved)
    }

    @Test
    fun `load opens the picker and the dialog stays when it comes back empty`() {
        var loads = 0
        rule.setContent {
            ProjectGateDialog(state = ProjectGateState("Untitled 1"), onLoad = { loads += 1 }, onSave = { _, _, _ -> })
        }
        rule.onNodeWithTag(TAG_LOAD).performClick()
        assertEquals(1, loads)
        // The picker returned nothing: the host keeps showing the same state, so the dialog is there.
        rule.onNodeWithTag(TAG_DIALOG).assertIsDisplayed()
        rule.onNodeWithTag(TAG_LOAD).assertIsDisplayed()
    }

    @Test
    fun `save defaults to the screen size`() {
        var size: Pair<Int, Int>? = null
        rule.setContent {
            ProjectGateDialog(
                state = ProjectGateState("Untitled 1", defaultWidth = 1200, defaultHeight = 2400),
                onLoad = {},
                onSave = { _, w, h -> size = w to h },
            )
        }
        rule.onNodeWithTag(TAG_SAVE).performScrollTo().performClick()
        assertEquals(1200 to 2400, size)
    }

    @Test
    fun `a chosen preset sets the canvas size`() {
        var size: Pair<Int, Int>? = null
        rule.setContent {
            ProjectGateDialog(
                state = ProjectGateState("Untitled 1"),
                onLoad = {},
                onSave = { _, w, h -> size = w to h },
            )
        }
        rule.onNodeWithTag("${TAG_SIZE_PREFIX}Print A4").performScrollTo().performClick()
        rule.onNodeWithTag(TAG_SAVE).performScrollTo().performClick()
        assertEquals(2480 to 3508, size)
    }

    @Test
    fun `custom size uses the typed fields`() {
        var size: Pair<Int, Int>? = null
        rule.setContent {
            ProjectGateDialog(
                state = ProjectGateState("Untitled 1"),
                onLoad = {},
                onSave = { _, w, h -> size = w to h },
            )
        }
        rule.onNodeWithTag("${TAG_SIZE_PREFIX}Custom").performScrollTo().performClick()
        rule.onNodeWithTag(TAG_CUSTOM_WIDTH).performTextReplacement("640")
        rule.onNodeWithTag(TAG_CUSTOM_HEIGHT).performTextReplacement("480")
        rule.onNodeWithTag(TAG_SAVE).performScrollTo().performClick()
        assertEquals(640 to 480, size)
    }

    @Test
    fun `import images opens the host picker and shows what came back`() {
        var picks = 0
        rule.setContent {
            ProjectGateDialog(
                state = ProjectGateState("Untitled 1"),
                onLoad = {},
                onSave = { _, _, _ -> },
                pickedImageCount = 3,
                onPickImages = { picks += 1 },
            )
        }
        rule.onNodeWithTag(TAG_IMAGES).performScrollTo().performClick()
        assertEquals(1, picks)
        rule.onNodeWithTag(TAG_IMAGES).assertTextEquals("Images: 3 — change…")
    }

    @Test
    fun `busy state replaces the actions`() {
        rule.setContent {
            ProjectGateDialog(
                state = ProjectGateState("Untitled 1", busyLabel = "Saving…"),
                onLoad = {},
                onSave = { _, _, _ -> },
            )
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
