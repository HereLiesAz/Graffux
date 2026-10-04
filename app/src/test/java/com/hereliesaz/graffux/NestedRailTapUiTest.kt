package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzHostActivityLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A single tap on an `azNestedRail` parent — declared the way MainActivity declares Transform,
 * Selection and Vector (no `reflectSelectionInParent`) — opens its nested rail and shows its children.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class NestedRailTapUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `single click on a nested rail item shows its children`() {
        rule.setContent {
            AzHostActivityLayout(navController = rememberNavController(), initiallyExpanded = false) {
                azRailItem(id = "main", text = "Main") { }
                azNestedRail(id = "grp.nested", text = "NestedParent", keepNestedRailOpen = true) {
                    azRailItem(id = "child.a", text = "ChildA") { }
                    azRailItem(id = "child.b", text = "ChildB") { }
                }
            }
        }
        rule.waitForIdle()
        fun shown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        assertFalse("children hidden before the tap", shown("ChildA"))

        rule.onAllNodesWithText("NestedParent").onFirst().performClick()
        rule.waitForIdle()
        assertTrue("first child shown after one tap", shown("ChildA"))
        assertTrue("second child shown after one tap", shown("ChildB"))
    }
}
