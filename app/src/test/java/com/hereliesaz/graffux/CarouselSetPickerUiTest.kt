package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The brush sheet's set picker and detents: AzRoller picks a set, the Favorites and previous-set
 * buttons jump, each set is its own list, the set is a grid at half and full, the brush cards share
 * one face, and the sheet shows a name pill (no grab handle) at peek only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class CarouselSetPickerUiTest {
    private companion object {
        const val MIN_CARD_DP = 60
        const val MAX_CARD_DP = 160
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val inputs = CarouselInputs(
        activeTool = Tool.BRUSH,
        activeBrushName = BuiltInBrushes.presets.first().name,
        builtInBrushes = BuiltInBrushes.presets,
        customBrushes = emptyList(),
        extensionBrushes = listOf("caps::0" to "Fat Cap", "caps::1" to "Skinny"),
        stabilizerLevel = 0,
        stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING,
        selectionShape = SelectionShape.ELLIPSE,
    )

    private var ui by mutableStateOf(CarouselUi(CarouselCategory.BRUSHES, sheetOpen = true))
    private val edited = mutableListOf<CarouselEntry>()

    private fun compose() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    CarouselSheet(
                        open = ui.sheetOpen,
                        onOpenChange = { ui = ui.copy(sheetOpen = it, showingCarousel = false) },
                        nameLabel = { carouselNamePill(true, ui.showingCarousel, inputs.activeBrushName) },
                    ) {
                        val sections = carouselTabSections(
                            carouselContextOf(ui.category), ui.category, inputs,
                            packNames = mapOf("caps" to "Spray Caps"),
                        )
                        val tab = selectedTabGroup(sections, ui.tab)
                        BottomCarousel(
                            ui = ui,
                            onUiChange = { ui = it },
                            content = CarouselContent(
                                tab?.entries.orEmpty(), Color.Red, Color.White, emptyMap(),
                                onEdit = { edited += it },
                            ),
                            onEntryClick = {},
                            onToggleFavorite = {},
                            tabs = sections,
                            selectedTab = tab?.key,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun pickInRoller(label: String) {
        rule.onNodeWithTag("AzRollerRight", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        // The roller's own field shows the current set too; the option is the last match.
        rule.onAllNodesWithText(label, useUnmergedTree = true).onLast().performClick()
        rule.waitForIdle()
    }

    @Test
    fun `the roller selects a set`() {
        compose()
        pickInRoller("Ink")
        assertEquals("ink", ui.tab)
    }

    @Test
    fun `the Favorites button jumps to Favorites`() {
        compose()
        rule.onNodeWithTag("carousel.quick.favorites").performClick()
        rule.waitForIdle()
        assertEquals(FAVORITES_SET_KEY, ui.tab)
        assertEquals(CarouselCategory.FAVORITES, ui.category)
    }

    @Test
    fun `the previous-set button returns to the set before, named for it`() {
        compose()
        pickInRoller("Ink")
        rule.onNodeWithTag("carousel.quick.favorites").performClick()
        rule.waitForIdle()
        val previous = hasTestTag("carousel.quick.previous") and hasText("Ink", substring = false)
        assertEquals(1, rule.onAllNodes(previous).fetchSemanticsNodes().size)
        rule.onNodeWithTag("carousel.quick.previous").performClick()
        rule.waitForIdle()
        assertEquals("ink", ui.tab)
        assertEquals("previous is now Favorites", FAVORITES_SET_KEY, ui.previousTab)
    }

    @Test
    fun `no duplicate sets in the roller`() {
        compose()
        rule.onNodeWithTag("AzRollerRight", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        // Favorites once in the list (plus its quick button); no "Starred", no type headings.
        assertEquals(2, rule.onAllNodesWithText("Favorites", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Starred", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("BASIC", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText("Spray Caps", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a set's list ends with no cross-set continuation`() {
        ui = ui.copy(tab = "ink", detent = CarouselDetent.FULL)
        compose()
        // Every Ink utensil, once, and nothing from any other set after it.
        assertEquals(com.hereliesaz.graffitixr.common.model.InkUtensil.entries.size, count("carousel.grid.card"))
        assertEquals(0, rule.onAllNodesWithText("Round", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Fat Cap", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `grid at half and full detent, carousel at short`() {
        compose()
        assertEquals(1, count("carousel.row"))
        assertEquals(0, count("carousel.grid.HALF"))
        ui = ui.copy(detent = CarouselDetent.HALF)
        rule.waitForIdle()
        assertEquals(0, count("carousel.row"))
        assertEquals(1, count("carousel.grid.HALF"))
        ui = ui.copy(detent = CarouselDetent.FULL)
        rule.waitForIdle()
        assertEquals(1, count("carousel.grid.FULL"))
        assertEquals(0, count("carousel.row"))
    }

    @Test
    fun `every brush card shares one layout, selected or not`() {
        ui = ui.copy(detent = CarouselDetent.HALF)
        compose()
        val cards = count("carousel.brushcard")
        assertTrue(cards >= 2)
        listOf("name", "stroke", "type", "engine", "edit").forEach {
            assertEquals("each card has its $it", cards, count("carousel.brushcard.$it"))
        }
        assertEquals(cards, rule.onAllNodesWithContentDescription("Edit brush", useUnmergedTree = true)
            .fetchSemanticsNodes().size)
        assertEquals("no More text on brush cards", 0, count("carousel.hero.more"))
    }

    @Test
    fun `the edit button opens Brush Studio for its brush`() {
        ui = ui.copy(detent = CarouselDetent.HALF)
        compose()
        rule.onAllNodesWithContentDescription("Edit brush", useUnmergedTree = true).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(1, edited.size)
        assertTrue(edited.single().action is CarouselAction.BuiltInBrush)
    }

    @Test
    fun `Ink cards have no edit button`() {
        ui = ui.copy(tab = "ink", detent = CarouselDetent.HALF)
        compose()
        assertTrue(count("carousel.brushcard") >= 2)
        assertEquals(0, count("carousel.brushcard.edit"))
    }

    @Test
    fun `no grab handle, and the name pill shows only at peek`() {
        ui = ui.copy(showingCarousel = false)
        compose()
        assertEquals(0, rule.onAllNodesWithContentDescription("Brush sheet", substring = true)
            .fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodes(hasTestTag("carousel.namePill") and hasText(inputs.activeBrushName!!),
            useUnmergedTree = true).fetchSemanticsNodes().size)
        for (detent in CarouselDetent.entries) {
            ui = ui.copy(showingCarousel = true, detent = detent)
            rule.waitForIdle()
            assertEquals("pill hidden at $detent", 0, count("carousel.namePill"))
        }
        assertNotEquals(null, carouselNamePill(true, false, "Round"))
        assertEquals("other sheets show no pill", null, carouselNamePill(false, false, "Round"))
    }

    @Test
    fun `a narrow brush card at font scale 2 draws without crashing`() {
        val entries = carouselEntries(CarouselCategory.BRUSHES, inputs).take(2) +
            carouselEntries(CarouselCategory.INK, inputs).take(2)
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(density.density, fontScale = 2f),
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    // Card heights stepped a dp at a time, so some leave the stroke a sliver of a
                    // few pixels: the case that handed coerceIn an empty range. Plus a bare preview
                    // one dp tall.
                    androidx.compose.foundation.layout.Row {
                        Box(Modifier.size(56.dp, 1.dp)) {
                            com.hereliesaz.graffitixr.feature.editor.BrushPreview(
                                entries.first().brush!!, Color.Red, Color.White, height = 1.dp,
                            )
                        }
                        (MIN_CARD_DP..MAX_CARD_DP).forEach { h ->
                            androidx.compose.foundation.layout.Column {
                                entries.forEach { entry ->
                                    Box(Modifier.size(56.dp, h.dp)) {
                                        BrushCardFace(
                                            entry,
                                            CarouselContent(entries, Color.Red, Color.White, emptyMap(), onEdit = {}),
                                            Color.White, {}, {},
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertEquals(entries.size * (MAX_CARD_DP - MIN_CARD_DP + 1), count("carousel.brushcard.stroke"))
    }
}
