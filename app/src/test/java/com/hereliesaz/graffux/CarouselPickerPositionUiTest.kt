package com.hereliesaz.graffux

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi", application = Application::class)
/**
 * The set picker's wheel (#691) must not move the cards: they sit exactly where they did with the
 * pre-wheel strip (measured from aa654e18^1's picker at this size, in px, 1078x2399), at peek and
 * at half. Also writes build/sheet-shots for a look by eye.
 */
class CarouselPickerPositionUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun probe(detent: CarouselDetent, tag: String, name: String, expectedTop: Float, expectedBottom: Float) {
        val inputs = CarouselInputs(
            activeTool = Tool.BRUSH, activeBrushName = BuiltInBrushes.presets[2].name,
            builtInBrushes = BuiltInBrushes.presets, customBrushes = emptyList(), extensionBrushes = emptyList(),
            stabilizerLevel = 0, stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
            smudgeMode = ColorSmudgeEngine.Mode.DULLING, selectionShape = SelectionShape.ELLIPSE,
        )
        val tabs = carouselTabSections(CarouselContext.BRUSHES, CarouselCategory.BRUSHES, inputs)
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    CarouselSheet(open = true, onOpenChange = {}, bottomInset = CarouselBottomInset) {
                        BottomCarousel(
                            ui = CarouselUi(
                                CarouselCategory.BRUSHES, sheetOpen = true, showingCarousel = true, detent = detent,
                            ),
                            onUiChange = {},
                            content = CarouselContent(
                                entries = carouselEntries(CarouselCategory.BRUSHES, inputs),
                                brushColor = Color.White, secondaryColor = Color.Red, extensionPreviews = emptyMap(),
                                heroState = HeroAdjustmentState(
                                    brushSize = 40f, brushFlow = 1f, brushOpacity = 1f, brushFeathering = 0f,
                                    smudgeRate = 0.5f, stabilizerLevel = 0, magicWandTolerance = 32,
                                ),
                            ),
                            onEntryClick = {}, onToggleFavorite = {}, tabs = tabs,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val cards = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals("$name cards' top", expectedTop, cards.minOf { it.top }, 1f)
        assertEquals("$name cards' bottom", expectedBottom, cards.maxOf { it.bottom }, 1f)
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File("build/sheet-shots").mkdirs()
        File("build/sheet-shots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `at peek the cards sit where they did before the wheel`() =
        probe(CarouselDetent.SHORT, "carousel.card.HERO", "peek", 1663f, 2188f)

    @Test
    fun `at half the cards sit where they did before the wheel`() =
        probe(CarouselDetent.HALF, "carousel.grid.card", "half", 1009f, 2188f)

    @Test
    fun `the wheel stays below the cards and only its quarter disc takes a swipe`() {
        probe(CarouselDetent.SHORT, "carousel.card.HERO", "peek", 1663f, 2188f)
        val wheel = rule.onNodeWithTag("carousel.wheel").fetchSemanticsNode().boundsInRoot
        assertTrue("wheel top ${wheel.top} under the cards", wheel.top >= 2188f)
        val pointed = { rule.onAllNodesWithTag("carousel.wheel.item").fetchSemanticsNodes()
            .map { it.boundsInRoot.center } }
        val before = pointed()
        // The box's corner away from the hub (top-left, the wheel on the right) is off the disc.
        rule.onNodeWithTag("carousel.wheel").performTouchInput {
            swipe(Offset(2f, 2f), Offset(40f, 2f), 200)
        }
        rule.waitForIdle()
        assertEquals(before, pointed())
    }
}
