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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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

/** The hero card's inline sliders: they drive the editor setting, never the row, and only the hero has them. */
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
        toolOptionsOpen = false,
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
                            onAdjust = { setter, v -> if (setter == HeroSetter.BRUSH_SIZE) brushSize = v },
                            onMore = { mores += it.key },
                        ),
                        history = CarouselHistory(undoCount = 0, redoCount = 0, onUndo = {}, onRedo = {}),
                        onEntryClick = { clicks += it.key },
                        onToggleFavorite = {},
                    )
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
    fun `dragging a hero slider changes the value and does not move the carousel`() {
        compose()
        val heroKey = "builtin.$selectedName"
        assertEquals(listOf(heroKey), previews())

        rule.onNodeWithTag("carousel.hero.slider.size", useUnmergedTree = true).performTouchInput {
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.9f, centerY), durationMillis = 600)
        }
        rule.waitForIdle()

        assertTrue("size rose from 20, was $brushSize", brushSize > 100f)
        assertEquals("the row stayed on the same hero", listOf(heroKey), previews())
        assertTrue("the slider drag selected nothing", clicks.isEmpty())
    }

    @Test
    fun `only the hero card shows controls, and More reports the hero`() {
        compose()
        val controls = rule.onAllNodesWithTag("carousel.hero.controls", useUnmergedTree = true)
        assertEquals(1, controls.fetchSemanticsNodes().size)
        listOf("size", "flow", "softness").forEach { id ->
            val sliders = rule.onAllNodes(hasTestTag("carousel.hero.slider.$id"), useUnmergedTree = true)
            assertEquals(1, sliders.fetchSemanticsNodes().size)
            val inHero = hasTestTag("carousel.hero.slider.$id") and hasAnyAncestor(hasTestTag("carousel.card.HERO"))
            rule.onNode(inHero, useUnmergedTree = true).assertExists()
        }
        listOf("MEDIUM", "SMALL").forEach { tier ->
            val controls = rule.onAllNodes(
                hasTestTag("carousel.hero.controls") and hasAnyAncestor(hasTestTag("carousel.card.$tier")),
                useUnmergedTree = true,
            )
            assertEquals(0, controls.fetchSemanticsNodes().size)
        }
        rule.onNodeWithTag("carousel.hero.more", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("builtin.$selectedName"), mores)
        assertTrue(clicks.isEmpty())
    }
}
