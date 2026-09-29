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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.CarouselItemSettings
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
 * "More" grows the hero card in place, on a layer in front of the carousel (in the app, its own
 * AzNavRail page; here, a sibling drawn after it): no dialog, the row underneath never moves, and
 * Back, Less or moving to another card shrinks it. Set CAROUSEL_MORE_SCREENSHOT to save a render.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class CarouselHeroExpandUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val selectedName = BuiltInBrushes.presets[2].name
    private val heroKey = "builtin.$selectedName"
    private val otherKey = "builtin.${BuiltInBrushes.presets[1].name}"
    private val stored = mutableStateMapOf(
        heroKey to CarouselItemSettings(size = 20f),
        otherKey to CarouselItemSettings(size = 150f, flow = 0.3f),
    )
    private val clicks = mutableListOf<String>()

    private var brushName by mutableStateOf(selectedName)

    private fun inputs() = CarouselInputs(
        activeTool = Tool.BRUSH,
        activeBrushName = brushName,
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
            val expansion = remember { HeroExpansion() }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF8A8A8A))) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        BottomCarousel(
                            ui = CarouselUi(CarouselCategory.BRUSHES, sheetOpen = true),
                            onUiChange = {},
                            content = CarouselContent(
                                entries = carouselEntries(CarouselCategory.BRUSHES, inputs()),
                                brushColor = Color.White,
                                secondaryColor = Color.Red,
                                extensionPreviews = emptyMap(),
                                heroState = HeroAdjustmentState(
                                    brushSize = 50f, brushFlow = 1f, brushOpacity = 1f, brushFeathering = 0f,
                                    smudgeRate = 0.5f, stabilizerLevel = 0, magicWandTolerance = 32,
                                ),
                                itemSettings = { e -> carouselSettingsKey(e)?.let { carouselItemSettingsFor(it, stored) } },
                                onAdjust = { e, setter, v ->
                                    carouselSettingsKey(e)?.let { key ->
                                        carouselItemSettingsFor(key, stored).adjusted(setter, v)?.let { stored[key] = it }
                                    }
                                },
                                onMore = {},
                            ),
                            history = CarouselHistory(undoCount = 1, redoCount = 1, onUndo = {}, onRedo = {}),
                            onEntryClick = { clicks += it.key },
                            onToggleFavorite = {},
                            expansion = expansion,
                        )
                    }
                    // The app's page in front of the carousel's.
                    ExpandedHeroLayer(expansion)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun expandedCount() = rule.onAllNodesWithTag("carousel.expanded", useUnmergedTree = true).fetchSemanticsNodes().size

    private fun bounds(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun more() {
        rule.onNodeWithTag("carousel.hero.more", useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    @Test
    fun `More grows the hero in place, over its neighbours, with no dialog`() {
        compose()
        val hero = bounds("carousel.card.HERO").single()
        val neighbours = bounds("carousel.card.MEDIUM") + bounds("carousel.card.SMALL")
        val preview = rule.onNodeWithTag("carousel.preview.$heroKey", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(0, expandedCount())

        more()

        assertEquals(1, expandedCount())
        rule.onAllNodes(isDialog()).assertCountEqualsZero()
        val card = bounds("carousel.expanded").single()
        assertTrue("wider than the hero", card.width > hero.width + 20f)
        assertTrue("taller, up over the preview: ${card.top} vs ${preview.top}", card.top <= preview.top + 1f)
        assertEquals("bottom stays on the hero's", hero.bottom, card.bottom, 2f)
        assertEquals("centred on the hero", hero.center.x, card.center.x, 2f)
        // Drawn over the neighbours, which have not moved or reflowed.
        assertEquals(neighbours, bounds("carousel.card.MEDIUM") + bounds("carousel.card.SMALL"))
        assertEquals(hero, bounds("carousel.card.HERO").single())
        // The full set, which includes the stabilizer the inline sliders leave to More.
        rule.onNodeWithTag("carousel.expanded.slider.stabilizer", useUnmergedTree = true).assertExists()
        assertTrue(clicks.isEmpty())

        System.getenv("CAROUSEL_MORE_SCREENSHOT")?.let { path ->
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        rule.onNodeWithTag("carousel.expanded.less", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(0, expandedCount())
    }

    private fun gapPx() = PreviewGap.value * rule.density.density

    private fun heroPreview() = bounds("carousel.preview.$heroKey")

    @Test
    fun `the stroke preview rides PreviewGap above the card, at rest, partway and grown`() {
        compose()
        val restPreview = heroPreview().single()
        val restHero = bounds("carousel.card.HERO").single()
        assertEquals("at rest, PreviewGap above the hero", restHero.top - gapPx(), restPreview.bottom, 1f)

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("carousel.hero.more", useUnmergedTree = true).performClick()
        // Reading the tree each frame syncs it, as a drawn frame would.
        repeat(PARTWAY_FRAMES) { rule.mainClock.advanceTimeByFrame(); expandedCount() }
        rule.waitForIdle()
        val midCard = bounds("carousel.expanded").single()
        val midPreview = bounds("carousel.expanded.preview").single()
        assertTrue("partway: ${midCard.height} between ${restHero.height} and grown", midCard.height > restHero.height + 2f)
        assertEquals("partway, the gap holds", midCard.top - gapPx(), midPreview.bottom, 1f)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        val card = bounds("carousel.expanded").single()
        val lifted = bounds("carousel.expanded.preview").single()
        assertTrue("it rose", lifted.bottom < restPreview.bottom - 50f)
        assertEquals("grown, the gap holds", card.top - gapPx(), lifted.bottom, 1f)

        System.getenv("CAROUSEL_MORE_PREVIEW_SCREENSHOT")?.let { path ->
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        rule.onNodeWithTag("carousel.expanded.less", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(0, expandedCount())
        assertEquals("back exactly where it was", listOf(restPreview), heroPreview())
    }

    private companion object {
        const val PARTWAY_FRAMES = 4
    }

    @Test
    fun `Back collapses it`() {
        compose()
        more()
        assertEquals(1, expandedCount())
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(0, expandedCount())
    }

    @Test
    fun `moving another card into the hero slot collapses it`() {
        compose()
        more()
        assertEquals(1, expandedCount())
        // Another brush is picked (the row scrolls it into the hero slot).
        brushName = BuiltInBrushes.presets[4].name
        rule.waitForIdle()
        rule.onNodeWithTag("carousel.preview.builtin.$brushName", useUnmergedTree = true).assertExists()
        assertEquals(0, expandedCount())
    }

    @Test
    fun `an expanded card's slider edits only that item`() {
        compose()
        more()
        val before = stored.getValue(otherKey)
        rule.onNodeWithTag("carousel.expanded.slider.size", useUnmergedTree = true).performTouchInput {
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.9f, centerY), durationMillis = 600)
        }
        rule.waitForIdle()
        assertTrue("hero size rose from 20, was ${stored.getValue(heroKey).size}", stored.getValue(heroKey).size > 100f)
        assertEquals(before, stored.getValue(otherKey))
        assertEquals("still open", 1, expandedCount())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() =
        assertEquals(0, fetchSemanticsNodes().size)
}
