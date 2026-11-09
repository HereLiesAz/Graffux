package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sheet's stages (tab list, carousel, half grid, full grid) were reached only by swiping. Each step is
 * now also a 48dp labelled button, built by the same [sheetStagesFor] MainActivity uses, over the content's
 * corners so the open sheet is no taller than before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class SheetStageTapUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var ui by mutableStateOf(CarouselUi(CarouselCategory.EFFECTS, sheetOpen = true, showingCarousel = false))
    private var designMode by mutableStateOf(false)
    private var withStages by mutableStateOf(true)
    private var fontScale by mutableStateOf(1f)

    private fun compose() {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselSheet(
                            open = ui.sheetOpen,
                            onOpenChange = { ui = ui.copy(sheetOpen = it, showingCarousel = false) },
                            stages = if (withStages) sheetStagesFor({ ui }, { ui = it }, designMode) else null,
                        ) { Box(Modifier.fillMaxWidth().height(120.dp).testTag("content")) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun has(tag: String) =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun click(tag: String) =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)
    private fun contentTop() = rule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `a card sheet climbs tab list, carousel, half and full grid by taps, and the up control is gone at the top`() {
        compose()
        tap("carousel.stage.up")
        assertEquals(Triple(true, true, CarouselDetent.SHORT), Triple(ui.sheetOpen, ui.showingCarousel, ui.detent))
        tap("carousel.stage.up")
        assertEquals(CarouselDetent.HALF, ui.detent)
        tap("carousel.stage.up")
        assertEquals(CarouselDetent.FULL, ui.detent)
        assertNull("no tap at the top stage", click("carousel.stage.up"))
    }

    @Test
    fun `show less descends and at the lowest stage the same control closes the sheet`() {
        compose()
        ui = ui.copy(showingCarousel = true, detent = CarouselDetent.FULL)
        rule.waitForIdle()
        tap("carousel.stage.down")
        assertEquals(CarouselDetent.HALF, ui.detent)
        tap("carousel.stage.down")
        assertEquals(CarouselDetent.SHORT, ui.detent)
        tap("carousel.stage.down")
        assertFalse("back at the tab list", ui.showingCarousel)
        val label = rule.onNodeWithTag("carousel.stage.down").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Close sheet"), label)
        tap("carousel.stage.down")
        assertFalse(ui.sheetOpen)
    }

    @Test
    fun `the brush page has no tab-list stage, so its carousel's control closes the sheet`() {
        ui = CarouselUi(CarouselCategory.BRUSHES, sheetOpen = true, showingCarousel = true)
        compose()
        val label = rule.onNodeWithTag("carousel.stage.down").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Close sheet"), label)
        tap("carousel.stage.down")
        assertFalse(ui.sheetOpen)
    }

    @Test
    fun `the design and photo sheets have no grid stages, so no show-more at the carousel`() {
        ui = CarouselUi(CarouselCategory.EFFECTS, sheetOpen = true, showingCarousel = true)
        designMode = true
        compose()
        assertNull("design sheet: no grid above the carousel", click("carousel.stage.up"))
        designMode = false
        ui = ui.copy(photo = true)
        rule.waitForIdle()
        assertNull("photo sheet: no grid above the carousel", click("carousel.stage.up"))
        // ...but their tab list still steps up to the carousel.
        ui = ui.copy(showingCarousel = false)
        rule.waitForIdle()
        tap("carousel.stage.up")
        assertTrue(ui.showingCarousel)
    }

    @Test
    fun `the buttons are named and 48dp, and each step has exactly one node`() {
        compose()
        val min = 48 * rule.density.density
        listOf("carousel.stage.up" to "Show more", "carousel.stage.down" to "Close sheet").forEach { (tag, label) ->
            val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
            assertEquals(listOf(label), node.config.getOrNull(SemanticsProperties.ContentDescription))
            assertTrue(node.size.width >= min && node.size.height >= min)
            assertEquals(1, rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size)
            assertTrue("a focusable button", click(tag) != null)
        }
        val duplicated = rule.onAllNodesWithTag("carousel.stageBar", useUnmergedTree = true)
            .fetchSemanticsNodes().count { it.config.getOrNull(SemanticsActions.CustomActions) != null }
        assertEquals("no second, action-only path", 0, duplicated)
        assertEquals(0, nodesWithCustomAction("Show more"))
    }

    private fun nodesWithCustomAction(label: String): Int {
        val all = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode): Int =
            (if (n.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true) 1 else 0) +
                n.children.sumOf { walk(it) }
        return walk(all)
    }

    @Test
    fun `the stage controls add no height, so the content sits at the same top with and without them`() {
        listOf(1f, 2f).forEach { scale ->
            fontScale = scale
            withStages = true
            if (!composed) { compose(); composed = true }
            rule.waitForIdle()
            val with = contentTop()
            withStages = false
            rule.waitForIdle()
            val without = contentTop()
            assertEquals("sheet top moved at font scale $scale", without, with, 0.5f)
            assertFalse(has("carousel.stage.up"))
        }
    }

    private var composed = false
}
