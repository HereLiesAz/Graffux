package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
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

/** Compact heroes hide settings; upward swipe reveals the same per-item controls in the grown card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class CarouselHeroSliderUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val selectedName = BuiltInBrushes.presets[2].name
    private var brushSize by mutableStateOf(20f)
    private val clicks = mutableListOf<String>()
    private val mores = mutableListOf<String>()
    private val expansion = HeroExpansion()

    private val inputs = CarouselInputs(
        activeTool = Tool.BRUSH,
        activeBrushName = selectedName,
        builtInBrushes = BuiltInBrushes.presets,
        customBrushes = emptyList(),
        extensionBrushes = emptyList(),
        stabilizerLevel = 0,
        stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING,
        selectionShape = SelectionShape.ELLIPSE,
    )

    private fun compose() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    BottomCarousel(
                        ui = CarouselUi(CarouselCategory.BRUSHES, sheetOpen = true),
                        onUiChange = {},
                        content = CarouselContent(
                            entries = carouselEntries(CarouselCategory.BRUSHES, inputs),
                            brushColor = Color.White,
                            secondaryColor = Color.Red,
                            extensionPreviews = emptyMap(),
                            heroState = HeroAdjustmentState(
                                brushSize = brushSize, brushFlow = 1f, brushOpacity = 1f, brushFeathering = 0f,
                                smudgeRate = 0.5f, stabilizerLevel = 0, magicWandTolerance = 32,
                            ),
                            onAdjust = { _, setter, v -> if (setter == HeroSetter.BRUSH_SIZE) brushSize = v },
                            onMore = { mores += it.key },
                        ),
                        history = CarouselHistory(undoCount = 0, redoCount = 0, onUndo = {}, onRedo = {}),
                        onEntryClick = { clicks += it.key },
                        onToggleFavorite = {},
                        expansion = expansion,
                    )
                    ExpandedHeroLayer(expansion)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun previews(): List<String> = rule
        .onAllNodes(SemanticsMatcher("carousel preview") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("carousel.preview.") == true
        }, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .map { it.config[SemanticsProperties.TestTag].removePrefix("carousel.preview.") }

    @Test
    fun `compact hero hides every adjustment until a swipe up`() {
        compose()
        val heroKey = "builtin.$selectedName"
        assertEquals(listOf(heroKey), previews())
        assertEquals(0, rule.onAllNodesWithTag("carousel.hero.controls", useUnmergedTree = true)
            .fetchSemanticsNodes().size)
        listOf("size", "flow", "softness").forEach { id ->
            assertEquals(0, rule.onAllNodesWithTag("carousel.hero.slider.$id", useUnmergedTree = true)
                .fetchSemanticsNodes().size)
        }
        rule.onNodeWithTag("carousel.card.HERO", useUnmergedTree = true).performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithTag("carousel.expanded", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("carousel.expanded.slider.size", useUnmergedTree = true).assertExists()
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun `only the center card expands on an upward swipe`() {
        compose()
        val center = "builtin.$selectedName"
        rule.onAllNodesWithTag("carousel.card.MEDIUM", useUnmergedTree = true)
            .onFirst().performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("carousel.expanded", useUnmergedTree = true)
            .fetchSemanticsNodes().size)
        assertEquals(listOf(center), previews())
        assertTrue("a side-card swipe cannot activate an item", clicks.isEmpty())

        rule.onNodeWithTag("carousel.card.HERO", useUnmergedTree = true)
            .performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithTag("carousel.expanded", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `expanded slider edits settings without changing active item`() {
        compose()
        rule.onNodeWithTag("carousel.hero.more", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("carousel.expanded.slider.size", useUnmergedTree = true).performTouchInput {
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.9f, centerY), durationMillis = 600)
        }
        rule.waitForIdle()
        assertTrue("size rose from 20, was $brushSize", brushSize > 100f)
        // The expanded overlay also draws the hero preview; both represent the same item.
        assertTrue(previews().contains("builtin.$selectedName"))
        assertTrue(clicks.isEmpty())
    }

    private fun cardBounds(tier: CarouselTier) = rule
        .onAllNodesWithTag("carousel.card.${tier.name}", useUnmergedTree = true)
        .fetchSemanticsNodes()
        .map { it.boundsInRoot }

    @Test
    fun `every card shares one vertical centre line`() {
        compose()
        val hero = cardBounds(CarouselTier.HERO).single()
        val sides = cardBounds(CarouselTier.MEDIUM) + cardBounds(CarouselTier.SMALL)
        assertTrue(cardBounds(CarouselTier.MEDIUM).size == 2 && cardBounds(CarouselTier.SMALL).isNotEmpty())
        sides.forEach { assertEquals(hero.center.y, it.center.y, 1f) }
        // The stroke preview sits wholly above the row, the tabs wholly below it.
        val row = rule.onNodeWithTag("carousel.row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val preview = rule.onNodeWithTag("carousel.preview.builtin.$selectedName", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("preview ${preview.bottom} above row ${row.top}", preview.bottom <= row.top + 1f)
        assertEquals("the row is as tall as the hero", row.height, hero.height, 1f)
        val undo = rule.onNodeWithContentDescription("Undo", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("tabs ${undo.top} below row ${row.bottom}", undo.top >= row.bottom - 1f)
    }

    @Test
    fun `card heights order small, medium, hero`() {
        compose()
        // Set CAROUSEL_HERO_SCREENSHOT to a .png path to also save a render with the sliders on.
        System.getenv("CAROUSEL_HERO_SCREENSHOT")?.let { path ->
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(path).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val hero = cardBounds(CarouselTier.HERO).single().height
        val medium = cardBounds(CarouselTier.MEDIUM).map { it.height }
        val small = cardBounds(CarouselTier.SMALL).map { it.height }
        assertTrue("small $small < medium $medium", small.max() < medium.min())
        assertTrue("medium $medium < hero $hero", medium.max() < hero)
        // The hero is about one and a half medium cards.
        assertTrue("hero/medium ${hero / medium.min()}", hero / medium.min() in 1.4f..1.6f)
        // More is on screen, inside the hero card.
        val card = cardBounds(CarouselTier.HERO).single()
        val more = bounds("carousel.hero.more")
        assertTrue("More inside the hero", more.bottom <= card.bottom + 1f && more.top >= card.top - 1f)
        rule.onAllNodesWithText("Built-in · round tip", useUnmergedTree = true).assertCountEquals(1)
    }

    private fun bounds(tag: String) =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun `compact title and details stay readable without adjustment controls`() {
        compose()
        val identity = bounds("carousel.hero.identity")
        val details = rule.onAllNodesWithText("Built-in · round tip", useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot
        assertTrue(details.bottom <= identity.bottom + 1f)
        assertEquals(0, rule.onAllNodesWithTag("carousel.hero.controls", useUnmergedTree = true)
            .fetchSemanticsNodes().size)
        val more = bounds("carousel.hero.more")
        assertTrue(more.bottom <= bounds("carousel.card.HERO").bottom + 1f)
    }
}
