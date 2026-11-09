package com.hereliesaz.graffitixr.feature.editor

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Open screen's delete control: a labelled 48dp button that names the project, a confirmation
 * that says what will be lost (and, for the open project, what happens next), and nothing deleted
 * until it is confirmed. The control was a 12dp-padded drawn "✕" with no label and no role.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class OpenProjectWindowUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val deleted = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private var dismissed = 0

    private fun compose(current: String? = null) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                OpenProjectWindow(
                    state = OpenScreenState(
                        inApp = listOf(
                            GraffitiProject(id = "a", name = "Wall"),
                            GraffitiProject(id = "b", name = "Door"),
                        ),
                    ),
                    currentProjectId = current,
                    onOpenProject = { opened += it },
                    onOpenFile = {},
                    onNew = {},
                    onChooseLocation = {},
                    onDelete = { deleted += it },
                    onDismiss = { dismissed++ },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun deleteFor(name: String) = rule.onNode(hasContentDescription("Delete $name"))

    @Test
    fun `each project has a labelled 48dp delete button`() {
        compose()
        assertEquals(2, rule.onAllNodesWithTag("open.delete").fetchSemanticsNodes().size)
        val node = deleteFor("Wall").fetchSemanticsNode()
        assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        val min = 48 * rule.density.density
        val touch = node.touchBoundsInRoot
        assertTrue("touch $touch", touch.width >= min && touch.height >= min)
    }

    @Test
    fun `nothing is deleted until it is confirmed, and Cancel keeps the project`() {
        compose()
        deleteFor("Wall").performClick()
        rule.onNodeWithText("Delete project?").assertExists()
        assertTrue(deleted.isEmpty())
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue("Cancel must not delete", deleted.isEmpty())
        rule.onNodeWithText("Delete project?").assertDoesNotExist()

        deleteFor("Door").performClick()
        rule.onNode(hasText("Delete") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(listOf("b"), deleted)
        assertEquals("deleting does not close the Open screen", 0, dismissed)
    }

    @Test
    fun `the message names the project and says more when it is the open one`() {
        compose(current = "a")
        deleteFor("Door").performClick()
        rule.onNode(hasText("\"Door\"", substring = true)).assertExists()
        rule.onNode(hasText("is open now", substring = true)).assertDoesNotExist()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        deleteFor("Wall").performClick()
        rule.onNode(hasText("is open now", substring = true)).assertExists()
    }

    @Test
    fun `a project's picture opens it and says so`() {
        compose()
        val open = rule.onNode(hasClickAction() and hasContentDescription("Wall"))
        val label = open.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
        assertEquals("Open Wall", label)
        open.performClick()
        assertEquals(listOf("a"), opened)
    }
}
