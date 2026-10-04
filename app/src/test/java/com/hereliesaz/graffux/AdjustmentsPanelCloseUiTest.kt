package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.design.components.AdjustmentsPanel
import com.hereliesaz.graffitixr.design.components.AdjustmentsState
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stray "✕" in the bottom-right corner of the editor: AdjustmentsPanel's close button, which
 * used to be composed whenever the document had any layer at all (the editor passes
 * `hasImage = layers.isNotEmpty()`), with no knobs open for it to close. The editor's default
 * screen — layers present, no Adjust/Colour panel — must have no close node; an open panel still
 * gets one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class AdjustmentsPanelCloseUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun closeNodesWith(showKnobs: Boolean, showColorBalance: Boolean, arMode: Boolean = false): Int {
        rule.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                AdjustmentsPanel(
                    state = AdjustmentsState(hasImage = true, isArMode = arMode),
                    showKnobs = showKnobs,
                    showColorBalance = showColorBalance,
                    isLandscape = false,
                    screenHeight = 891.dp,
                    onOpacityChange = {}, onBrightnessChange = {}, onContrastChange = {},
                    onSaturationChange = {}, onColorBalanceRChange = {}, onColorBalanceGChange = {},
                    onColorBalanceBChange = {}, onAdjustmentStart = {}, onAdjustmentEnd = {},
                    onDismiss = {},
                    strings = rememberAppStrings(),
                )
            }
        }
        rule.waitForIdle()
        return rule.onAllNodesWithContentDescription("Close", substring = true, ignoreCase = true)
            .fetchSemanticsNodes().size
    }

    @Test
    fun `default editor screen with layers has no close button`() {
        assertEquals(0, closeNodesWith(showKnobs = false, showColorBalance = false))
    }

    @Test
    fun `AR mode with no panel open has no close button`() {
        assertEquals(0, closeNodesWith(showKnobs = false, showColorBalance = false, arMode = true))
    }

    @Test
    fun `open adjust knobs keep their close button`() {
        assertEquals(1, closeNodesWith(showKnobs = true, showColorBalance = false))
    }

    @Test
    fun `open colour balance keeps its close button`() {
        assertEquals(1, closeNodesWith(showKnobs = false, showColorBalance = true))
    }
}
