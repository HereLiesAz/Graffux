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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
        ui = ui.copy(showingCarousel = false, tab = "levels")
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

    @Test
    fun `at the half grid the star of the first row still toggles its favorite, and the control still works`() {
        val entries = (0 until 6).map { i ->
            CarouselEntry("e$i", "Brush $i", CarouselAction.BuiltInBrush("Round"), selected = false)
        }
        val toggled = mutableListOf<String>()
        ui = CarouselUi(
            CarouselCategory.EFFECTS, sheetOpen = true, showingCarousel = true, detent = CarouselDetent.HALF,
        )
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    CarouselSheet(
                        open = true,
                        onOpenChange = {},
                        stages = sheetStagesFor({ ui }, { ui = it }, designMode = false),
                    ) {
                        BottomCarousel(
                            ui = ui,
                            onUiChange = { ui = it },
                            content = CarouselContent(
                                entries = entries,
                                brushColor = androidx.compose.ui.graphics.Color.White,
                                secondaryColor = androidx.compose.ui.graphics.Color.Black,
                                extensionPreviews = emptyMap(),
                            ),
                            onEntryClick = {},
                            onToggleFavorite = { toggled += it.key },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val up = rule.onNodeWithTag("carousel.stage.up").fetchSemanticsNode().boundsInRoot
        val stars = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Add to favorites"), true)
            .fetchSemanticsNodes()
        assertTrue("the grid shows stars", stars.isNotEmpty())
        stars.forEach {
            assertFalse("a star under the control: ${it.boundsInRoot} vs $up", it.boundsInRoot.overlaps(up))
        }
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Add to favorites"), true)[0].performClick()
        assertEquals(listOf("e0"), toggled)
        assertEquals("tapping the star must not step the sheet", CarouselDetent.HALF, ui.detent)
        tap("carousel.stage.up")
        assertEquals(CarouselDetent.FULL, ui.detent)
    }

    // ---- the set wheel's corner ----------------------------------------------------------------

    private val setNames = listOf("Favorites", "Round", "Flat", "Ink", "My Brushes", "Spray Caps")
    private val picked = mutableListOf<String>()
    private var wheelOnRight by mutableStateOf(true)

    private fun composeWithWheel() {
        val sections = listOf(
            CarouselTabSection(
                "ALL",
                setNames.map { CarouselTabGroup(it.lowercase(), it, CarouselCategory.EFFECTS, emptyList()) },
            ),
        )
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalSetWheelOnRight provides wheelOnRight) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselSheet(
                            open = true,
                            onOpenChange = { ui = ui.copy(sheetOpen = it, showingCarousel = false) },
                            bottomInset = 16.dp,
                            stages = sheetStagesFor({ ui }, { ui = it }, designMode = false),
                        ) { CarouselTabList(sections, "favorites", onSelect = { picked += it.label }) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertNoControlOverWheel() {
        val items = rule.onAllNodesWithTag("carousel.wheel.item", useUnmergedTree = true).fetchSemanticsNodes()
        listOf("carousel.stage.up", "carousel.stage.down").filter { has(it) }.forEach { tag ->
            val control = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            items.forEach {
                assertFalse("$tag $control covers a wheel name ${it.boundsInRoot}", it.boundsInRoot.overlaps(control))
            }
        }
    }

    @Test
    fun `at the tab list the controls sit in the corner the wheel leaves free, in both handednesses`() {
        listOf(true, false).forEach { right ->
            wheelOnRight = right
            if (!composedWheel) { composeWithWheel(); composedWheel = true }
            rule.waitForIdle()
            val both = has("carousel.stage.up") && has("carousel.stage.down")
            assertTrue("both controls fit beside the wheel at 411dp", both)
            assertNoControlOverWheel()
            // A real tap on a set still selects it rather than stepping the sheet.
            val before = picked.size
            val wheel = rule.onNodeWithTag("carousel.wheel").fetchSemanticsNode().boundsInRoot
            val target = rule.onAllNodesWithTag("carousel.wheel.item", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .first {
                    it.config.getOrNull(SemanticsProperties.Text)?.single()?.text == if (right) "Round" else "Flat"
                }
            val at = androidx.compose.ui.geometry.Offset(
                target.boundsInRoot.center.x - wheel.left,
                target.boundsInRoot.center.y - wheel.top,
            )
            rule.onNodeWithTag("carousel.wheel").performTouchInput { click(at) }
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(300)
            assertTrue("a tap on a set picks it (right-handed=$right)", picked.size > before)
            assertFalse("and did not step the sheet", ui.showingCarousel)
            picked.clear()
        }
    }

    private var composedWheel = false

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h800dp-xxhdpi", application = Application::class)
    fun `on a 320dp screen Close sheet is always there, 48dp, clear of the wheel, in both handednesses`() {
        listOf(true, false).forEach { right ->
            wheelOnRight = right
            if (!composedWheel) { composeWithWheel(); composedWheel = true }
            rule.waitForIdle()
            assertTrue("Close sheet must exist (right-handed=$right)", has("carousel.stage.down"))
            val node = rule.onNodeWithTag("carousel.stage.down", useUnmergedTree = true).fetchSemanticsNode()
            val min = 48 * rule.density.density
            assertTrue("48dp", node.size.width >= min && node.size.height >= min)
            assertEquals(listOf("Close sheet"), node.config.getOrNull(SemanticsProperties.ContentDescription))
            assertNoControlOverWheel()
            assertFalse("no room for show more", has("carousel.stage.up"))
            // The control actually closes the sheet.
            tap("carousel.stage.down")
            assertFalse("closing from the tab list", ui.sheetOpen)
            ui = ui.copy(sheetOpen = true)
            rule.waitForIdle()
        }
    }

    // ---- the photo sheet's first opening -----------------------------------------------------------

    @Test
    fun `the photo sheet has nothing to show more of until a tab is chosen`() {
        ui = CarouselUi(CarouselCategory.EFFECTS, sheetOpen = true, showingCarousel = false, photo = true, tab = null)
        compose()
        assertNull("no show-more with no tab", click("carousel.stage.up"))
        assertEquals(listOf("Close sheet"), rule.onNodeWithTag("carousel.stage.down").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription))
        ui = ui.copy(tab = "levels")
        rule.waitForIdle()
        tap("carousel.stage.up")
        assertTrue("with a tab it steps up", ui.showingCarousel)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h800dp-xxhdpi", application = Application::class)
    fun `at 320dp the photo sheet with no tab offers Close and no show-more`() {
        ui = CarouselUi(CarouselCategory.EFFECTS, sheetOpen = true, showingCarousel = false, photo = true, tab = null)
        compose()
        assertTrue(has("carousel.stage.down"))
        assertFalse(has("carousel.stage.up"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi", application = Application::class)
    fun `on a 360dp phone only Close sheet fits beside the wheel and it is there, 48dp and clear of the names`() {
        composeWithWheel()
        assertTrue("Close sheet exists", has("carousel.stage.down"))
        assertFalse("Show more needs 384dp or more at this stage", has("carousel.stage.up"))
        assertNoControlOverWheel()
    }
}
