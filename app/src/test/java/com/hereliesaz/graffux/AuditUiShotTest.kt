@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffux

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.graffitixr.feature.editor.PaletteMixerDialog
import com.hereliesaz.graffitixr.feature.editor.PaletteMixerHost
import com.hereliesaz.graffitixr.feature.editor.newMixerBoard
import com.hereliesaz.graffitixr.feature.editor.LongOperationOverlay
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import com.hereliesaz.graffitixr.common.azphalt.PaletteBoard
import com.hereliesaz.graffitixr.feature.editor.RecipeLibrary
import com.hereliesaz.graffitixr.feature.editor.MixActions
import com.hereliesaz.graffitixr.feature.editor.SavedMix
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.assertTextContains
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the surfaces the UI/UX audit fixes changed. Set AUDIT_SCREENSHOTS to a directory to save
 * the renders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class AuditUiShotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        val dir = System.getenv("AUDIT_SCREENSHOTS") ?: return
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun dp(px: Float) = Math.round(px / rule.density.density)

    @Test
    fun `the layer menu is sectioned, with destructive rows last and 48dp rows`() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0F))) {
                    MenuPanel(
                        rows = buildMenu {
                            section("Layer")
                            listItem("Adjust") {}
                            listItem("Blend mode") {}
                            section("Arrange")
                            listItem("Move up") {}
                            listItem("Move down") {}
                            section("Properties")
                            check("Lock transparency", true) {}
                            check("Clip to layer below", false) {}
                            section("Layout")
                            check("Horizontal: Left & right", true) {}
                            section("Remove")
                            destructiveItem("Clear layer") {}
                            destructiveItem("Delete") {}
                        },
                        onDismiss = {},
                        title = "Fill",
                        maxHeight = 800.dp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        save("layer-menu")
        assertTrue(dp(rule.onNodeWithText("Move up").fetchSemanticsNode().boundsInRoot.height) >= 48)
        rule.onNodeWithText("Lock transparency").assertIsDisplayed()
    }

    @Test
    fun `the progress overlay is labelled and cancellable`() {
        var cancelled = false
        rule.setContent {
            GraffitiXRTheme {
                LongOperationOverlay(label = "Making poster PDF…", onCancel = { cancelled = true })
            }
        }
        rule.waitForIdle()
        save("progress")
        rule.onNodeWithText("Making poster PDF…").assertIsDisplayed()
        rule.onNodeWithTag("editor.progress.cancel").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun `the paint mixer names its tubes and offers a labelled digital hex match`() {
        rule.setContent {
            GraffitiXRTheme {
                PaletteMixerDialog(
                    board = newMixerBoard(),
                    currentColor = Color(0xFFFF3EA5),
                    secondaryColor = Color.Black,
                    onSelectColor = {},
                    onSelectSecondaryColor = {},
                    onSwapColors = {},
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        save("paint-mixer")
        rule.onNodeWithContentDescription("Cadmium Red, opaque, strong tinter").assertIsDisplayed()
        rule.onNodeWithContentDescription("Foreground brush").assertIsDisplayed()
        rule.onNodeWithTag("mixer.board").assertIsDisplayed()
        rule.onNodeWithTag("mixer.hex").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Digital: match a colour").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a message's OK is a 48dp target`() {
        val controller = MessageController().apply { show(message = "Saved poster.pdf to Downloads", actionLabel = "Open", onAction = {}) }
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MessageHost(controller, okLabel = "OK")
            }
        }
        rule.waitForIdle()
        val ok = rule.onNodeWithTag("chrome.message.ok").fetchSemanticsNode().boundsInRoot
        assertTrue(dp(ok.height) >= 48 && dp(ok.width) >= 48)
        rule.onNodeWithTag("chrome.message.action").assertIsDisplayed()
    }

    @Test
    fun `undo and redo stay on screen in landscape`() {
        val layers = listOf(Layer(id = "a", name = "A"))
        val state = EditorUiState(undoCount = 1, layers = layers, activeLayerId = "a", brushSize = 24f, activeColor = Color(0xFFFF3EA5), activeTool = Tool.NONE)
        val actions = object : ToolRailActions {
            override fun setActiveTool(tool: Tool) = Unit
            override fun onColorClicked() = Unit
            override fun resetViewport() = Unit
            override fun onUndoClicked() = Unit
            override fun onRedoClicked() = Unit
            override fun setBrushSize(size: Float) = Unit
            override fun setBrushFeathering(feathering: Float) = Unit
        }
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // A landscape phone's height: 360dp less the bars.
                Box(Modifier.fillMaxSize().padding(bottom = 844.dp - 320.dp).background(Color(0xFF0E0E0F))) {
                    ToolRail(
                        vm = actions, uiState = state, lit = emptySet(), secondary = emptySet(),
                        callbacks = ToolRailCallbacks({}, {}, {}),
                        modifier = Modifier.padding(start = RailSideInset, top = RailTopInset),
                    )
                }
            }
        }
        rule.waitForIdle()
        save("rail-landscape")
        val redo = rule.onNodeWithTag("rail.history.redo").fetchSemanticsNode().boundsInRoot
        assertTrue("redo bottom ${dp(redo.bottom)}", dp(redo.bottom) <= 320)
    }

    @Test
    fun `squeezing a tube and dipping the brush puts that paint in the brush hand`() {
        val board = newMixerBoard()
        var applied: Color? = null
        rule.setContent {
            GraffitiXRTheme {
                PaletteMixerDialog(
                    board = board,
                    currentColor = Color.White,
                    secondaryColor = Color.Black,
                    onSelectColor = { applied = it },
                    onSelectSecondaryColor = {},
                    onSwapColors = {},
                    onDismiss = {},
                )
            }
        }
        // Paint the board and load the brush the way a touch would, then confirm what the user is told.
        board.squeeze(4, 30f, 30f, 7f, 0.9f)
        board.dip(30f, 30f)
        rule.waitForIdle()
        assertTrue(board.brushRecipe().tubes.first().first.id == "cadmium-red")
    }

    @Test
    fun `closing and reopening the mixer keeps the paint on the board`() {
        var show by androidx.compose.runtime.mutableStateOf(true)
        var board: com.hereliesaz.graffitixr.common.azphalt.PaletteBoard? = null
        rule.setContent {
            GraffitiXRTheme {
                PaletteMixerHost(
                    show = show,
                    currentColor = Color.White,
                    secondaryColor = Color.Black,
                    onSelectColor = {},
                    onSelectSecondaryColor = {},
                    onSwapColors = {},
                    onDismiss = {},
                    onBoard = { board = it },
                )
            }
        }
        rule.waitForIdle()
        val first = checkNotNull(board)
        first.squeeze(4, 30f, 30f, 7f, 0.9f)
        first.dip(30f, 30f)
        show = false
        rule.waitForIdle()
        show = true
        rule.waitForIdle()
        assertTrue("the same board comes back", board === first)
        assertTrue(first.recipeAt(30, 30).tubes.first().first.id == "cadmium-red")
        assertTrue("and the brush still holds its paint", first.brush.amount[0] > 0f)
    }

    @Test
    fun `the mixer's saved-mixes strip saves, recalls, renames and deletes with labelled 48dp actions`() {
        val board = newMixerBoard()
        board.squeeze(2, 30f, 30f, 8f, 1f)
        repeat(3) { board.dip(30f, 30f) }
        val recipe = requireNotNull(com.hereliesaz.graffitixr.common.azphalt.PaletteRecipes.fromBrush(board, "m1", "Sap green"))
        var mixes by androidx.compose.runtime.mutableStateOf(
            listOf(SavedMix(recipe, RecipeLibrary.Scope.PROJECT)),
        )
        val saved = mutableListOf<Pair<String, RecipeLibrary.Scope>>()
        val renamed = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        rule.setContent {
            GraffitiXRTheme {
                PaletteMixerDialog(
                    board = board, currentColor = Color.White, secondaryColor = Color.Black,
                    onSelectColor = {}, onSelectSecondaryColor = {}, onSwapColors = {}, onDismiss = {},
                    mixes = mixes,
                    mixActions = MixActions(
                        save = { r, s -> saved += r.name to s },
                        rename = { m, n -> renamed += n },
                        delete = { m -> deleted += m.recipe.id; mixes = mixes - m },
                    ),
                )
            }
        }
        rule.waitForIdle()
        // save: both scopes are offered as labelled buttons and announce the result
        rule.onNodeWithTag("mixer.saveMix.PROJECT").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.mixStatus").assertTextContains("Saved Mix 2 to this project", substring = true)
        rule.onNodeWithTag("mixer.saveMix.APP").performScrollTo().performClick()
        assertEquals(listOf("Mix 2" to RecipeLibrary.Scope.PROJECT, "Mix 2" to RecipeLibrary.Scope.APP), saved)
        // the chip names the mix, where it is kept, and how to use it
        rule.onNodeWithContentDescription("Sap green, saved in this project. Select for actions").performScrollTo().performClick()
        // recall onto the background brush: the brush colour changes and the result is announced
        board.clean(PaletteBoard.Clean.RINSE)
        rule.onNodeWithContentDescription("Recall Sap green onto the background brush").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.mixStatus").assertTextContains("Recalled Sap green onto the background brush", substring = true)
        assertEquals(1, board.hand)
        assertTrue(board.brush.amount[0] > 0f)
        // rename and delete are labelled actions, and delete asks first
        rule.onNodeWithContentDescription("Rename Sap green").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.renameField").performTextClearance()
        rule.onNodeWithTag("mixer.renameField").performTextInput("Moss")
        rule.onNodeWithTag("mixer.confirmRename").performClick()
        assertEquals(listOf("Moss"), renamed)
        rule.onNodeWithContentDescription("Delete Sap green").performScrollTo().performClick()
        assertTrue("nothing deleted before the confirmation", deleted.isEmpty())
        rule.onNodeWithTag("mixer.confirmDelete").performClick()
        assertEquals(listOf("m1"), deleted)
        rule.onNodeWithTag("mixer.mixStatus").assertTextContains("Deleted Sap green", substring = true)
    }

    @Test
    fun `a mix made against different pigment data is refused with the reason announced`() {
        val board = newMixerBoard()
        board.squeeze(2, 30f, 30f, 8f, 1f)
        repeat(3) { board.dip(30f, 30f) }
        val good = requireNotNull(com.hereliesaz.graffitixr.common.azphalt.PaletteRecipes.fromBrush(board, "m1", "Old"))
        val stale = good.copy(slots = good.slots.mapIndexed { i, s -> if (i == 0) s.copy(dataVersion = s.dataVersion + 7) else s })
        rule.setContent {
            GraffitiXRTheme {
                PaletteMixerDialog(
                    board = board, currentColor = Color.White, secondaryColor = Color.Black,
                    onSelectColor = {}, onSelectSecondaryColor = {}, onSwapColors = {}, onDismiss = {},
                    mixes = listOf(SavedMix(stale, RecipeLibrary.Scope.APP)),
                )
            }
        }
        rule.waitForIdle()
        board.clean(PaletteBoard.Clean.RINSE)
        rule.onNodeWithContentDescription("Old, saved in all projects. Select for actions").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Recall Old onto the foreground brush").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.mixStatus").assertTextContains("made with different pigment data", substring = true)
        assertEquals("the brush was left alone", 0f, board.brush.amount[0], 0f)
    }

    @Test
    fun `a refused recall leaves the selected brush where it was`() {
        val board = newMixerBoard()
        board.squeeze(2, 30f, 30f, 8f, 1f)
        repeat(3) { board.dip(30f, 30f) }
        val good = requireNotNull(com.hereliesaz.graffitixr.common.azphalt.PaletteRecipes.fromBrush(board, "m1", "Old"))
        val stale = good.copy(slots = good.slots.mapIndexed { i, s -> if (i == 0) s.copy(dataVersion = s.dataVersion + 7) else s })
        rule.setContent {
            GraffitiXRTheme {
                PaletteMixerDialog(
                    board = board, currentColor = Color.White, secondaryColor = Color.Black,
                    onSelectColor = {}, onSelectSecondaryColor = {}, onSwapColors = {}, onDismiss = {},
                    mixes = listOf(SavedMix(stale, RecipeLibrary.Scope.APP)),
                )
            }
        }
        rule.waitForIdle()
        assertEquals(0, board.hand)
        rule.onNodeWithContentDescription("Old, saved in all projects. Select for actions").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Recall Old onto the background brush").performScrollTo().performClick()
        rule.onNodeWithTag("mixer.mixStatus").assertTextContains("made with different pigment data", substring = true)
        assertEquals("the refused recall must not switch the brush in hand", 0, board.hand)
    }

    @Test
    fun `save current colour saves the editor's selected colour, by hand, not the palette brush's`() {
        val board = newMixerBoard() // the palette brush is empty: its colour is not the editor's
        val saved = mutableListOf<Int>()
        val editorForeground = Color(0xFFFF8800)
        val editorBackground = Color(0xFF3366CC)
        rule.setContent {
            GraffitiXRTheme {
                PaletteMixerDialog(
                    board = board, currentColor = editorForeground, secondaryColor = editorBackground,
                    onSelectColor = {}, onSelectSecondaryColor = {}, onSwapColors = {}, onDismiss = {},
                    mixActions = MixActions(saveColour = { saved += it.toArgb() }),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("mixer.saveColour").performScrollTo().performClick()
        board.hand = 1
        rule.waitForIdle()
        rule.onNodeWithTag("mixer.saveColour").performScrollTo().performClick()
        assertEquals(listOf(editorForeground.toArgb(), editorBackground.toArgb()), saved)
    }
}
