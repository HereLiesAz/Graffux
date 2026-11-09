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
import androidx.compose.ui.test.onNodeWithTag
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
 * The brush sheet has no picker strip any more (its packs are on the rail's pack wheel,
 * RailPackWheel.kt), and nothing reserves the strip's room: the cards sit where the pre-#691 layout
 * had them less the strip (StripHeight) and the column gap above it, i.e. 158px lower than the
 * strip-era pins (1663 / 1009 / 2188, measured from aa654e18^1 at this size, 1078x2399). Pinned at
 * peek and at half. Also writes build/sheet-shots for a look by eye.
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
    fun `at peek the cards sit at the sheet's foot with no strip under them`() =
        probe(CarouselDetent.SHORT, "carousel.card.HERO", "peek", PEEK_TOP, CARDS_BOTTOM)

    @Test
    fun `at half the cards sit at the sheet's foot with no strip under them`() =
        probe(CarouselDetent.HALF, "carousel.grid.card", "half", HALF_TOP, CARDS_BOTTOM)

    @Test
    fun `the brush sheet shows no picker and nothing pushes the cards up`() {
        probe(CarouselDetent.SHORT, "carousel.card.HERO", "peek", PEEK_TOP, CARDS_BOTTOM)
        rule.onNodeWithTag("carousel.tabs").assertDoesNotExist()
        rule.onNodeWithTag("carousel.wheel").assertDoesNotExist()
        rule.onNodeWithTag("carousel.quick.favorites").assertDoesNotExist()
        rule.onNodeWithTag("carousel.quick.previous").assertDoesNotExist()
        val cards = rule.onAllNodesWithTag("carousel.card.HERO", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("never above the strip-era pin", cards.minOf { it.boundsInRoot.top } >= STRIP_ERA_PEEK_TOP)
    }

    private companion object {
        /** Where the cards sat with the picker strip under them (aa654e18^1, and #691 to #703). */
        const val STRIP_ERA_PEEK_TOP = 1663f
        /** The strip's 56dp and the column gap above it, at 420dpi. */
        const val STRIP_ROOM = 158f
        const val PEEK_TOP = STRIP_ERA_PEEK_TOP + STRIP_ROOM
        /** The grid's top strip (48dp at 420dpi) that the stage controls own; the grid's height is unchanged. */
        const val STAGE_STRIP = 126f
        const val HALF_TOP = 1009f + STRIP_ROOM + STAGE_STRIP
        const val CARDS_BOTTOM = 2188f + STRIP_ROOM
    }
}
