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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.SelectionShape
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.StabilizerAlgorithm
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sheet's stages, wired the way MainActivity wires them: shut shows only the grab pill; a swipe
 * up shows only the tab list; a tab opens its carousel; a swipe down steps back to the tab list and a
 * swipe up from there brings the same carousel back. Set TAB_LIST_SCREENSHOTS to a directory to save
 * a render of each stage.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class CarouselTabListUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val inputs = CarouselInputs(
        activeTool = Tool.BRUSH,
        activeBrushName = BuiltInBrushes.presets.first().name,
        builtInBrushes = BuiltInBrushes.presets,
        customBrushes = emptyList(),
        extensionBrushes = listOf("caps::0" to "Fat Cap", "caps::1" to "Skinny", "drip::0" to "Drop"),
        stabilizerLevel = 0,
        stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING,
        selectionShape = SelectionShape.ELLIPSE,
    )

    private var ui by mutableStateOf(CarouselUi(CarouselCategory.BRUSHES, sheetOpen = false, showingCarousel = false))

    private fun compose() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0F)), contentAlignment = Alignment.BottomCenter) {
                    CarouselSheet(
                        open = ui.sheetOpen,
                        onOpenChange = { ui = ui.copy(sheetOpen = it, showingCarousel = false) },
                        bottomInset = 16.dp,
                        stages = SheetStages(
                            onStepUp = { if (!ui.showingCarousel) ui = ui.copy(showingCarousel = true) },
                            onStepDown = { ui.showingCarousel.also { if (it) ui = ui.copy(showingCarousel = false) } },
                        ),
                    ) {
                        val sections = carouselTabSections(
                            carouselContextOf(ui.category), ui.category, inputs,
                            packNames = mapOf("caps" to "Spray Caps", "drip" to "Gravity"),
                        )
                        val tab = selectedTabGroup(sections, ui.tab)
                        BottomCarousel(
                            ui = ui,
                            onUiChange = { ui = it },
                            content = CarouselContent(
                                entries = tab?.entries.orEmpty(),
                                brushColor = Color(0xFFFF3EA5),
                                secondaryColor = Color.White,
                                extensionPreviews = emptyMap(),
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

    private fun screenshot(name: String) {
        val dir = System.getenv("TAB_LIST_SCREENSHOTS") ?: return
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun carouselShown() = rule.onAllNodesWithTag("carousel.row").fetchSemanticsNodes().isNotEmpty()
    private fun tabsShown() = rule.onAllNodesWithTag("carousel.tabs").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `shut, tab list, carousel, and back`() {
        compose()
        screenshot("1-shut")
        assertFalse(carouselShown())

        rule.onNodeWithContentDescription("Brush sheet, shut").performClick()
        rule.waitForIdle()
        screenshot("2-tab-list")
        assertTrue(ui.sheetOpen)
        assertTrue(tabsShown())
        assertFalse("brushes stay hidden at the tab list", carouselShown())

        rule.onAllNodesWithTag("carousel.tab.pack.caps")[0].performClick()
        rule.waitForIdle()
        screenshot("3-carousel")
        assertEquals("pack.caps", ui.tab)
        assertTrue(carouselShown())

        rule.onNodeWithTag("carousel.tabs").performTouchInput {
            swipeDown(startY = top, endY = bottom + 900f, durationMillis = 100)
        }
        rule.waitForIdle()
        assertTrue("a swipe down steps back to the tab list", ui.sheetOpen && !ui.showingCarousel)
        assertFalse(carouselShown())

        rule.onNodeWithTag("carousel.tabs").performTouchInput {
            swipeUp(startY = bottom, endY = top - 300f, durationMillis = 100)
        }
        rule.waitForIdle()
        assertTrue("a swipe up brings the carousel back", ui.showingCarousel)
        assertEquals("pack.caps", ui.tab)
        assertTrue(carouselShown())
    }

    @Test
    fun `the brush tab list offers only brushes`() {
        compose()
        rule.onNodeWithContentDescription("Brush sheet, shut").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("carousel.tab.ink").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithTag("carousel.tab.fx.tools").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("carousel.tab.options").fetchSemanticsNodes().isEmpty())
    }
}
