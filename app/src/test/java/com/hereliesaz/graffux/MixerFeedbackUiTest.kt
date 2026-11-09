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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.hereliesaz.graffitixr.common.azphalt.PaletteRecipes
import com.hereliesaz.graffitixr.feature.editor.PaletteMixerDialog
import com.hereliesaz.graffitixr.feature.editor.RecipeLibrary
import com.hereliesaz.graffitixr.feature.editor.SavedMix
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

    private var mixes = emptyList<SavedMix>()

    private fun compose() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PaletteMixerDialog(
                    mixes = mixes,
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
        status().assertTextContains("Dipped the foreground brush", substring = true)
    }

    @Test
    fun `dipping or mixing with nothing to lift says so instead of reporting a dip or a mix`() {
        compose()
        boardAction("Dip the brush")
        status().assertTextContains("Nothing picked up", substring = true)
        rule.onNodeWithTag("mixer.mixOnce").performScrollTo().performClick()
        status().assertTextContains("holds no paint, so there was nothing to mix", substring = true)
    }

    @Test
    fun `wiping or rinsing an empty brush says so and asks nothing`() {
        compose()
        rule.onNodeWithTag("mixer.wipe").performScrollTo().performClick()
        status().assertTextContains("The foreground brush holds no paint", substring = true)
        rule.onNodeWithTag("mixer.rinse").performScrollTo().performClick()
        status().assertTextContains("The foreground brush holds no paint", substring = true)
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
        status().assertTextContains("Rinsed the foreground brush", substring = true)
        rule.onNodeWithTag("mixer.rinseRow").assertDoesNotExist()
    }

    @Test
    fun `wiping a loaded brush says so, and a swap says the brushes swapped`() {
        compose()
        loadBrush()
        rule.onNodeWithTag("mixer.wipe").performScrollTo().performClick()
        status().assertTextContains("Wiped the foreground brush", substring = true)
        rule.onNodeWithText("Swap").performScrollTo().performClick()
        assertEquals(1, swaps)
        status().assertTextContains("Swapped the foreground and background brushes", substring = true)
    }

    @Test
    fun `the same action twice is a changed string on the one live-region node`() {
        compose()
        fun spoken() = status().fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.single().text
        boardAction("Squeeze the chosen tube")
        val first = spoken()
        val node = status().fetchSemanticsNode().id
        boardAction("Squeeze the chosen tube")
        val second = spoken()
        assertTrue("a repeated line must be a different string", first != second)
        assertEquals("only an invisible character differs", first.trim('\u200B'), second.trim('\u200B'))
        assertEquals("one stable node, not a replaced one", node, status().fetchSemanticsNode().id)
    }

    @Test
    fun `pressing Rinse announces the question`() {
        compose()
        loadBrush()
        rule.onNodeWithTag("mixer.rinse").performScrollTo().performClick()
        status().assertTextContains("Rinse the foreground brush?", substring = true)
    }

    @Test
    fun `a cancelled drag announces what it did`() {
        compose()
        rule.onNodeWithTag("mixer.board").performScrollTo().performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(60f, 0f))
            cancel()
        }
        rule.waitForIdle()
        status().assertTextContains("Squeezed", substring = true)
    }

    @Test
    fun `a dip that adds nothing to a full brush is not reported as a dip`() {
        compose()
        boardAction("Squeeze the chosen tube")
        repeat(40) { boardAction("Dip the brush") }
        val full = board.brush.amount[0]
        boardAction("Dip the brush")
        assertEquals("the brush is full, so the dip lifts nothing", full, board.brush.amount[0], 0f)
        status().assertTextContains("Nothing picked up", substring = true)
    }

    @Test
    fun `ending a drag announces it`() {
        compose()
        rule.onNodeWithTag("mixer.board").performScrollTo().performTouchInput { swipeRight() }
        rule.waitForIdle()
        status().assertTextContains("Squeezed", substring = true)
        loadBrush()
        rule.onNodeWithText("Dip and mix").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.board").performScrollTo().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        status().assertTextContains("Mixed with the foreground brush", substring = true)
    }

    @Test
    fun `changing hand closes the rinse question and it does not come back`() {
        compose()
        loadBrush()
        rule.onNodeWithTag("mixer.rinse").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.rinseRow").assertExists()
        rule.onNodeWithTag("mixer.hand.1").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.rinseRow").assertDoesNotExist()
        status().assertTextContains("Background brush in hand", substring = true)
        rule.onNodeWithTag("mixer.hand.0").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.rinseRow").assertDoesNotExist()
    }

    @Test
    fun `a board gesture or a Match closes the rinse question`() {
        compose()
        loadBrush()
        rule.onNodeWithTag("mixer.rinse").performScrollTo().performClick()
        boardAction("Squeeze the chosen tube")
        rule.onNodeWithTag("mixer.rinseRow").assertDoesNotExist()

        rule.onNodeWithTag("mixer.rinse").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.rinseRow").assertExists()
        rule.onNodeWithTag("mixer.hex").performScrollTo().performTextInput("#FF8800")
        rule.onNodeWithText("Match").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.rinseRow").assertDoesNotExist()
        status().assertTextContains("loaded on the foreground brush", substring = true)
    }

    @Test
    fun `an unreadable colour is announced and leaves the brush alone`() {
        compose()
        loadBrush()
        val held = board.brush.amount[0]
        rule.onNodeWithTag("mixer.hex").performScrollTo().performTextInput("hello")
        rule.onNodeWithText("Match").performScrollTo().performClick()
        status().assertTextContains("Not a colour", substring = true)
        assertEquals(held, board.brush.amount[0], 0f)
    }

    @Test
    fun `recalling a mix onto the other brush closes a pending rinse and never rinses that brush`() {
        // A saved mix made from a loaded foreground brush; the board is set up directly, before composing.
        board.squeeze(0, 48f, 48f, 7f, 0.9f)
        board.dip(48f, 48f)
        val recipe = requireNotNull(PaletteRecipes.fromBrush(board, "m1", "Sap"))
        mixes = listOf(SavedMix(recipe, RecipeLibrary.Scope.PROJECT))
        compose()
        val foreground = board.brushes[0].amount[0]
        assertTrue(foreground > 0f)
        rule.onNodeWithTag("mixer.rinse").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.rinseRow").assertExists()

        rule.onNodeWithTag("mixer.mix.m1").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.recall.1").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals("the recall puts the background brush in hand", 1, board.hand)
        rule.onNodeWithTag("mixer.rinseRow").assertDoesNotExist()
        assertTrue("the recalled paint is on the background brush", board.brushes[1].amount[0] > 0f)
        assertEquals("the foreground brush was never rinsed", foreground, board.brushes[0].amount[0], 0f)
    }
}
