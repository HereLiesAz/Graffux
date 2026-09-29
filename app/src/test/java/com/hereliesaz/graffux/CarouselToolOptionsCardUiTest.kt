@file:Suppress("MaxLineLength")

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import java.io.File

/**
 * The Tool Options window is gone: every setting it held is on the item's own expanded card, and
 * the rail's Tool Options (here [HeroExpansion.request], what `openToolOptionsCard` calls after
 * switching page) grows the card of whatever is in hand. Set CAROUSEL_SMUDGE_SCREENSHOT to save a render.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class CarouselToolOptionsCardUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val adjusted = mutableListOf<Pair<HeroSetter, Float>>()
    private val chosen = mutableListOf<Pair<HeroChoiceSetter, Int>>()
    private val toggled = mutableListOf<Pair<HeroToggleSetter, Boolean>>()
    private var category by mutableStateOf(CarouselCategory.BRUSHES)
    private val expansion = HeroExpansion()

    private fun inputs(tool: Tool, shape: SelectionShape = SelectionShape.ELLIPSE) = CarouselInputs(
        activeTool = tool,
        activeBrushName = BuiltInBrushes.presets[2].name,
        builtInBrushes = BuiltInBrushes.presets,
        customBrushes = emptyList(),
        extensionBrushes = emptyList(),
        stabilizerLevel = 25,
        stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING,
        selectionShape = shape,
    )

    private fun compose(input: CarouselInputs) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF8A8A8A))) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        BottomCarousel(
                            ui = CarouselUi(category, sheetOpen = true),
                            onUiChange = { category = it.category },
                            content = CarouselContent(
                                entries = carouselEntries(category, input),
                                brushColor = Color.White,
                                secondaryColor = Color.Red,
                                extensionPreviews = emptyMap(),
                                heroState = HeroAdjustmentState(
                                    brushSize = 50f, brushFlow = 1f, brushOpacity = 1f, brushFeathering = 0f,
                                    smudgeRate = 0.5f, stabilizerLevel = 25, magicWandTolerance = 32,
                                    more = HeroMoreState(smudgeMode = ColorSmudgeEngine.Mode.DULLING, selectionFeatherPx = 4f),
                                ),
                                onAdjust = { _, setter, v -> adjusted += setter to v },
                                onChoose = { _, setter, i -> chosen += setter to i },
                                onToggle = { _, setter, on -> toggled += setter to on },
                                onMore = {},
                            ),
                            history = CarouselHistory(undoCount = 0, redoCount = 0, onUndo = {}, onRedo = {}),
                            onEntryClick = {},
                            onToggleFavorite = {},
                            expansion = expansion,
                        )
                    }
                    ExpandedHeroLayer(expansion)
                }
            }
        }
        rule.waitForIdle()
    }

    /** What the rail's Tool Options does: switch to the target page, then ask for its card. */
    private fun railToolOptions(input: CarouselInputs) {
        val (page, key) = toolOptionsTarget(input)!!
        category = page
        expansion.request(key)
        rule.waitForIdle()
    }

    private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()

    private fun swipeSlider(id: String) {
        node("carousel.expanded.slider.$id").performTouchInput {
            swipe(Offset(width * 0.05f, centerY), Offset(width * 0.95f, centerY), durationMillis = 400)
        }
        rule.waitForIdle()
    }

    @Test
    fun `the rail's Tool Options grows Smudge's card, and every moved control drives its setting`() {
        val input = inputs(Tool.SMUDGE)
        compose(input)
        assertEquals(0, rule.onAllNodesWithTag("carousel.expanded", useUnmergedTree = true).fetchSemanticsNodes().size)

        railToolOptions(input)

        assertEquals(CarouselCategory.EFFECTS, category)
        assertEquals(SMUDGE_SETTINGS_KEY, expansion.expandedKey)
        rule.onNodeWithTag("carousel.expanded", useUnmergedTree = true).assertExists()
        assertEquals("no dialog", 0, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        listOf("Smudge", "Wet mix", "Sampling", "Brush", "Stabilizer").forEach {
            rule.onNodeWithTag("carousel.expanded.section.$it", useUnmergedTree = true).assertExists()
        }

        System.getenv("CAROUSEL_SMUDGE_SCREENSHOT")?.let { path ->
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        node("carousel.expanded.choice.smudgeMode.0").performClick()
        rule.waitForIdle()
        assertEquals(HeroChoiceSetter.SMUDGE_MODE to 0, chosen.last())

        mapOf(
            "radius" to HeroSetter.SMUDGE_RADIUS,
            "chargeDecay" to HeroSetter.SMUDGE_CHARGE_DECAY,
            "dilution" to HeroSetter.SMUDGE_DILUTION,
            "pickup" to HeroSetter.SMUDGE_PICKUP,
        ).forEach { (id, setter) ->
            swipeSlider(id)
            assertEquals("$id drives $setter", setter, adjusted.last().first)
        }

        mapOf(
            "pigment" to HeroToggleSetter.SMUDGE_PIGMENT_MIXING,
            "carryAlpha" to HeroToggleSetter.SMUDGE_CARRY_ALPHA,
            "sampleMerged" to HeroToggleSetter.SMUDGE_SAMPLE_MERGED,
        ).forEach { (id, setter) ->
            node("carousel.expanded.toggle.$id").performClick()
            rule.waitForIdle()
            assertEquals(setter to true, toggled.last())
        }

        node("carousel.expanded.choice.stabilizerAlgorithm.1").performClick()
        rule.waitForIdle()
        assertEquals(HeroChoiceSetter.STABILIZER_ALGORITHM to 1, chosen.last())
    }

    @Test
    fun `the rail's Tool Options grows the brush in hand on the Brushes page`() {
        val input = inputs(Tool.BRUSH)
        category = CarouselCategory.EFFECTS
        compose(input)
        railToolOptions(input)
        assertEquals(CarouselCategory.BRUSHES, category)
        assertEquals("builtin.${BuiltInBrushes.presets[2].name}", expansion.expandedKey)
        rule.onNodeWithTag("carousel.expanded.slider.flow", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `selection's feather and threshold are on the selection shape's card`() {
        val input = inputs(Tool.SELECT, SelectionShape.AUTOMATIC)
        compose(input)
        railToolOptions(input)
        assertEquals(CarouselCategory.OPTIONS, category)
        assertEquals("selectShape.AUTOMATIC", expansion.expandedKey)
        swipeSlider("feather")
        assertEquals(HeroSetter.SELECTION_FEATHER, adjusted.last().first)
        assertTrue(adjusted.last().second > 4f)
        swipeSlider("threshold")
        assertEquals(HeroSetter.WAND_TOLERANCE, adjusted.last().first)
    }
}
