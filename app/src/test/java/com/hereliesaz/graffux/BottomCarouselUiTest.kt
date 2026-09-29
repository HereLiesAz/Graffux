package com.hereliesaz.graffux

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The carousel as composed: one hero card (the only card showing details), and a star that toggles
 * a favorite without selecting the item. Set CAROUSEL_SCREENSHOT to a .png path to also save a render.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class BottomCarouselUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val selectedName = BuiltInBrushes.presets[2].name

    private fun inputs(favorites: List<String>) = CarouselInputs(
        activeTool = Tool.BRUSH,
        activeBrushName = selectedName,
        builtInBrushes = BuiltInBrushes.presets,
        customBrushes = emptyList(),
        extensionBrushes = emptyList(),
        stabilizerLevel = 0,
        stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING,
        selectionShape = SelectionShape.ELLIPSE,
        toolOptionsOpen = false,
        favorites = favorites,
    )

    @Test
    fun `one hero card, centred on the selection, and the star toggles without selecting`() {
        val clicks = mutableListOf<String>()
        var favorites by mutableStateOf(emptyList<String>())
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val ui = remember { CarouselUi(CarouselCategory.BRUSHES, sheetOpen = true) }
                Box(Modifier.fillMaxSize().background(Color(0xFF303030)), contentAlignment = Alignment.BottomCenter) {
                    CarouselSheet(open = ui.sheetOpen, onOpenChange = {}) {
                        BottomCarousel(
                            ui = ui,
                            onUiChange = {},
                            content = CarouselContent(
                                entries = carouselEntries(ui.category, inputs(favorites)),
                                brushColor = Color.White,
                                secondaryColor = Color.Red,
                                extensionPreviews = emptyMap(),
                            ),
                            history = CarouselHistory(undoCount = 1, redoCount = 0, onUndo = {}, onRedo = {}),
                            onEntryClick = { clicks += it.key },
                            onToggleFavorite = { favorites = toggleCarouselFavorite(favorites, it.key) },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        System.getenv("CAROUSEL_SCREENSHOT")?.let { path ->
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // small · medium · HERO · medium · small at 411dp.
        fun count(tier: CarouselTier) =
            rule.onAllNodesWithTag("carousel.card.${tier.name}", useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals(1, count(CarouselTier.HERO))
        assertEquals(2, count(CarouselTier.MEDIUM))
        assertTrue(count(CarouselTier.SMALL) >= 2)
        // Details render only on the hero card, so exactly one card carries a details line.
        assertEquals(1, rule.onAllNodesWithText("Built-in · round tip").fetchSemanticsNodes().size)
        // And it is the selected brush's card: its name is on screen.
        assertTrue(rule.onAllNodesWithText(selectedName).fetchSemanticsNodes().isNotEmpty())

        rule.onAllNodesWithContentDescription("Add to favorites")[0].performClick()
        rule.waitForIdle()
        assertEquals(1, favorites.size)
        assertTrue("the star must not select the item", clicks.isEmpty())
    }

    /** A live harness: tapping or settling on a brush selects it, as `EditorViewModel` would. */
    private inner class Harness(val category: CarouselCategory = CarouselCategory.BRUSHES) {
        var brushName by mutableStateOf(selectedName)
        var stabilizerLevel by mutableStateOf(0)
        val clicks = mutableListOf<String>()
        val entries get() = carouselEntries(category, liveInputs())

        private fun liveInputs() =
            inputs(emptyList()).copy(activeBrushName = brushName, stabilizerLevel = stabilizerLevel)

        init {
            rule.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        BottomCarousel(
                            ui = CarouselUi(category, sheetOpen = true),
                            onUiChange = {},
                            content = CarouselContent(
                                entries = carouselEntries(category, liveInputs()),
                                brushColor = Color.White,
                                secondaryColor = Color.Red,
                                extensionPreviews = emptyMap(),
                            ),
                            history = CarouselHistory(undoCount = 0, redoCount = 0, onUndo = {}, onRedo = {}),
                            onEntryClick = { entry ->
                                clicks += entry.key
                                when (val a = entry.action) {
                                    is CarouselAction.BuiltInBrush -> brushName = a.name
                                    is CarouselAction.StabilizerLevel -> stabilizerLevel = a.level
                                    else -> Unit
                                }
                            },
                            onToggleFavorite = {},
                        )
                    }
                }
            }
            rule.waitForIdle()
        }

        fun previews(): List<String> = rule
            .onAllNodes(SemanticsMatcher("carousel preview") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("carousel.preview.") == true
            }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag].removePrefix("carousel.preview.") }

        /** Drags the row one item towards higher indices, slowly, so it settles by position alone. */
        fun dragOneItemForward() {
            rule.onNodeWithTag("carousel.row").performTouchInput {
                val y = centerY
                swipe(Offset(width * 0.75f, y), Offset(width * 0.75f - STEP_PX, y), durationMillis = 1_500)
            }
            rule.waitForIdle()
        }
    }

    @Test
    fun `the preview shows the settled hero, and follows the row to the next one`() {
        val h = Harness()
        val n = h.entries.indexOfFirst { it.selected }
        assertEquals(listOf(h.entries[n].key), h.previews())

        h.dragOneItemForward()

        assertEquals(listOf(h.entries[n + 1].key), h.previews())
        // And the card that settled in the hero slot became the selection.
        assertEquals(BuiltInBrushes.presets[n + 1].name, h.brushName)
        assertEquals(listOf(h.entries[n + 1].key), h.clicks)
    }

    @Test
    fun `tapping a non-hero card scrolls it into the hero slot, then selects it`() {
        val h = Harness()
        val n = h.entries.indexOfFirst { it.selected }
        val next = BuiltInBrushes.presets[n + 1].name
        rule.onAllNodesWithContentDescription(next)[0].performClick()
        rule.waitForIdle()
        assertEquals(next, h.brushName)
        assertEquals("one selection, no loop", listOf(h.entries[n + 1].key), h.clicks)
        assertEquals(listOf(h.entries[n + 1].key), h.previews())
    }

    @Test
    fun `an external selection change re-centres the row without clicking anything`() {
        val h = Harness()
        val target = BuiltInBrushes.presets.last().name
        h.brushName = target // e.g. the rail's brush picker, or undo restoring a brush.
        rule.waitForIdle()
        assertEquals(listOf(h.entries.last().key), h.previews())
        assertTrue(h.clicks.isEmpty())
        assertTrue(rule.onAllNodesWithText(target).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `scrolling onto an options stop does not run it`() {
        val h = Harness(CarouselCategory.OPTIONS)
        val before = h.stabilizerLevel
        h.dragOneItemForward()
        assertEquals(before, h.stabilizerLevel)
        assertTrue(h.clicks.isEmpty())
        assertTrue("options have no stroke preview", h.previews().isEmpty())
    }

    private companion object {
        // One item's travel at 411dp xxhdpi: hero/2 + spacing + medium/2 (see carouselSlot).
        const val STEP_PX = 1233f * (0.42f + 0.16f) / 2f + 18f
    }
}
