package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hereliesaz.graffitixr.design.components.GraffuxChoiceChip
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The selected option of a choice row must be unmistakable: contrast, a role and a selected state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChoiceChipContrastTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun ratio(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun selectedChipExposesRadioRoleAndSelectedState() {
        rule.setContent {
            GraffitiXRTheme {
                GraffuxChoiceChip(selected = true, onClick = {}, label = "On")
                GraffuxChoiceChip(selected = false, onClick = {}, label = "Off")
            }
        }
        rule.onNodeWithText("On").assertIsSelected()
        rule.onNodeWithText("Off").assertIsNotSelected()
        val role = rule.onNodeWithText("On", useUnmergedTree = false)
            .fetchSemanticsNode().config[SemanticsProperties.Role]
        assertEquals(Role.RadioButton, role)
    }

    @Test
    fun themeColoursMeetWcag() {
        var primary = Color.Unspecified
        var onPrimary = Color.Unspecified
        var onSurface = Color.Unspecified
        var surface = Color.Unspecified
        rule.setContent {
            GraffitiXRTheme(dynamicColor = false) {
                val s = androidx.compose.material3.MaterialTheme.colorScheme
                primary = s.primary; onPrimary = s.onPrimary; onSurface = s.onSurface; surface = s.surface
            }
        }
        assertTrue("label on selected >= 4.5", ratio(primary, onPrimary) >= 4.5f)
        assertTrue("selected vs unselected container >= 3", ratio(primary, surface) >= 3f)
        assertTrue("unselected label >= 4.5", ratio(onSurface, surface) >= 4.5f)
    }
}
