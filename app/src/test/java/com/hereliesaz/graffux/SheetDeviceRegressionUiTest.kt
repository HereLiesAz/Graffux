package com.hereliesaz.graffux

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
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
 * The brush sheet's defects seen on a phone after #687–#692: a second "Favorites" where the
 * previous-set button belongs, quick buttons far from the wheel, the wheel's edge names cut, the
 * selected brush card blank.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", application = Application::class)
class SheetDeviceRegressionUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val names = listOf("Favorites", "Round", "Flat", "Ink", "My Brushes", "Spray Caps")
    private val keys = listOf(FAVORITES_SET_KEY, "basic.round", "basic.flat", "ink", "studio", "pack.caps")
    private val sections = listOf(
        CarouselTabSection(
            "ALL",
            keys.zip(names).map { (k, n) -> CarouselTabGroup(k, n, CarouselCategory.BRUSHES, emptyList()) },
        ),
    )
    private var selected by mutableStateOf<String?>(FAVORITES_SET_KEY)

    private fun composePicker(start: String) {
        selected = start
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalSetWheelOnRight provides true) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselTabList(sections, selected, onSelect = { selected = it.key })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun `item 2 - the names on the wheel's edge spokes are not cut by its box`() {
        // Round under the pointer puts Ink on the flat bottom spoke (180°).
        composePicker(start = "basic.round")
        val wheel = bounds("carousel.wheel")
        val ink = rule.onNode(hasTestTag("carousel.wheel.item") and hasText("Ink")).fetchSemanticsNode().boundsInRoot
        // A labelLarge line is 20dp: half of it falls below the spoke.
        val halfLine = 10f * rule.density.density
        assertTrue(
            "Ink's line (centre ${ink.center.y}) must end inside the wheel (bottom ${wheel.bottom})",
            ink.center.y + halfLine <= wheel.bottom + 0.5f,
        )
    }

    private val brushName = BuiltInBrushes.presets[2].name
    private val inputs = CarouselInputs(
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

    /** The brush sheet as the editor hosts it: the carousel in [CarouselSheet]. */
    private fun composeSheet(showingCarousel: Boolean) {
        val tabs = carouselTabSections(CarouselContext.BRUSHES, CarouselCategory.BRUSHES, inputs)
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    CarouselSheet(
                        open = true,
                        onOpenChange = {},
                        nameLabel = { carouselNamePill(true, showingCarousel, brushName) },
                    ) {
                        BottomCarousel(
                            ui = CarouselUi(
                                CarouselCategory.BRUSHES, sheetOpen = true, showingCarousel = showingCarousel,
                            ),
                            onUiChange = {},
                            content = CarouselContent(
                                entries = carouselEntries(CarouselCategory.BRUSHES, inputs),
                                brushColor = Color.White,
                                secondaryColor = Color.Red,
                                extensionPreviews = emptyMap(),
                                heroState = HeroAdjustmentState(
                                    brushSize = 40f, brushFlow = 1f, brushOpacity = 1f, brushFeathering = 0f,
                                    smudgeRate = 0.5f, stabilizerLevel = 0, magicWandTolerance = 32,
                                ),
                            ),
                            onEntryClick = {},
                            onToggleFavorite = {},
                            tabs = tabs,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun luminance(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    @Test
    fun `item 3 - the selected brush card shows its white stroke inside the card`() {
        composeSheet(showingCarousel = true)
        val card = rule.onAllNodesWithTag("carousel.brushcard").fetchSemanticsNodes()
        assertTrue(card.isNotEmpty())
        val root = rule.onRoot().captureToImage().asAndroidBitmap()
        val selectedCard = rule.onAllNodesWithTag("carousel.brushcard.stroke", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .minBy { kotlin.math.abs(it.center.x - root.width / 2f) }
        // The stroke area of the centred (selected) card: a white stroke on a dark card, so both a
        // near-white and a clearly darker pixel must be there. On a light fill it is all light.
        var white = 0
        var dark = 0
        for (y in selectedCard.top.toInt() until selectedCard.bottom.toInt().coerceAtMost(root.height)) {
            for (x in selectedCard.left.toInt() until selectedCard.right.toInt().coerceAtMost(root.width)) {
                val l = luminance(root.getPixel(x, y))
                if (l > 0.95f) white++
                if (l < 0.5f) dark++
            }
        }
        assertTrue("stroke pixels: $white", white > 50)
        assertTrue("card fill pixels darker than the stroke: $dark", dark > 50)
    }

    @Test
    fun `item 4 - the sheet has no rotation slider`() {
        composeSheet(showingCarousel = false)
        assertTrue(rule.onAllNodesWithTag("rotationSlider").fetchSemanticsNodes().isEmpty())
        assertEquals(1, rule.onAllNodesWithTag("carousel.namePill", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    /** Renders the sheet at peek and half (1080x2400) to build/sheet-shots for a look by eye. */
    @Test
    fun `screenshots of the sheet at peek and half`() {
        val dir = File("build/sheet-shots").apply { mkdirs() }
        for ((name, carousel) in listOf("peek" to false, "half" to true)) {
            composeSheetOnce(carousel)
            val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(1080, bmp.width)
        }
    }

    private var shownCarousel by mutableStateOf(false)
    private var composed = false

    private fun composeSheetOnce(carousel: Boolean) {
        shownCarousel = carousel
        if (!composed) {
            composed = true
            val tabs = carouselTabSections(CarouselContext.BRUSHES, CarouselCategory.BRUSHES, inputs)
            rule.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselSheet(
                            open = true,
                            onOpenChange = {},
                            bottomInset = CarouselBottomInset,
                            nameLabel = { carouselNamePill(true, shownCarousel, brushName) },
                        ) {
                            BottomCarousel(
                                ui = CarouselUi(
                                    CarouselCategory.BRUSHES, sheetOpen = true, showingCarousel = shownCarousel,
                                    tab = "basic", previousTab = FAVORITES_SET_KEY,
                                ),
                                onUiChange = {},
                                content = CarouselContent(
                                    entries = carouselEntries(CarouselCategory.BRUSHES, inputs),
                                    brushColor = Color.White,
                                    secondaryColor = Color.Red,
                                    extensionPreviews = emptyMap(),
                                    heroState = HeroAdjustmentState(
                                        brushSize = 40f, brushFlow = 1f, brushOpacity = 1f, brushFeathering = 0f,
                                        smudgeRate = 0.5f, stabilizerLevel = 0, magicWandTolerance = 32,
                                    ),
                                ),
                                onEntryClick = {},
                                onToggleFavorite = {},
                                tabs = tabs,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

}
