package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hereliesaz.graffitixr.feature.editor.PaletteMixerDialog
import com.hereliesaz.graffitixr.feature.editor.newMixerBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The mixer says what each board action did (a polite live region, since the board changes only as
 * pixels), offers the board's touches as accessibility actions, and asks before it rinses away the
 * paint on the brush in hand. A rinse, a wipe or a swap used to change the brush and say nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class MixerFeedbackUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val board = newMixerBoard()
    private var swaps = 0

    private fun compose() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PaletteMixerDialog(
                    board = board,
                    currentColor = Color.Red,
                    secondaryColor = Color.Blue,
                    onSelectColor = {},
                    onSelectSecondaryColor = {},
                    onSwapColors = { swaps++ },
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun status() = rule.onNodeWithTag("mixer.status", useUnmergedTree = true)

    private fun boardAction(label: String) {
        val actions = rule.onNodeWithTag("mixer.board").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsActions.CustomActions,
        ]
        val action = actions.firstOrNull { it.label == label }
        assertTrue("no board action \"$label\" among ${actions.map { it.label }}", action != null)
        rule.runOnUiThread { action!!.action() }
        rule.waitForIdle()
    }

    private fun loadBrush() {
        boardAction("Squeeze the chosen tube")
        boardAction("Dip the brush")
        assertTrue("the dip must load the brush", board.brush.amount[0] > 0f)
    }

    @Test
    fun `the status line is a polite live region`() {
        compose()
        val live = status().fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        assertEquals(LiveRegionMode.Polite, live)
    }

    @Test
    fun `the board's touches are accessibility actions that say what they did`() {
        compose()
        boardAction("Squeeze the chosen tube")
        status().assertTextContains("Squeezed", substring = true)
        boardAction("Dip the brush")
        status().assertTextContains("Dipped the foreground brush")
    }

    @Test
    fun `wiping or rinsing an empty brush says so and asks nothing`() {
        compose()
        rule.onNodeWithTag("mixer.wipe").performScrollTo().performClick()
        status().assertTextContains("The foreground brush holds no paint")
        rule.onNodeWithTag("mixer.rinse").performScrollTo().performClick()
        status().assertTextContains("The foreground brush holds no paint")
        rule.onNodeWithTag("mixer.rinseRow").assertDoesNotExist()
    }

    @Test
    fun `rinsing a loaded brush asks first, and Keep it leaves the paint`() {
        compose()
        loadBrush()
        rule.onNodeWithTag("mixer.rinse").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.rinseRow").assertExists()
        assertTrue("nothing is rinsed before it is confirmed", board.brush.amount[0] > 0f)
        rule.onNodeWithText("Keep it").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.rinseRow").assertDoesNotExist()
        assertTrue("Keep it keeps the paint", board.brush.amount[0] > 0f)
    }

    @Test
    fun `confirming the rinse removes the paint and says so`() {
        compose()
        loadBrush()
        rule.onNodeWithTag("mixer.rinse").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.confirmRinse").performScrollTo().performClick()
        assertEquals(0f, board.brush.amount[0], 0f)
        status().assertTextContains("Rinsed the foreground brush")
        rule.onNodeWithTag("mixer.rinseRow").assertDoesNotExist()
    }

    @Test
    fun `wiping a loaded brush says so, and a swap says the brushes swapped`() {
        compose()
        loadBrush()
        rule.onNodeWithTag("mixer.wipe").performScrollTo().performClick()
        status().assertTextContains("Wiped the foreground brush")
        rule.onNodeWithText("Swap").performScrollTo().performClick()
        assertEquals(1, swaps)
        status().assertTextContains("Swapped the foreground and background brushes")
    }
}
