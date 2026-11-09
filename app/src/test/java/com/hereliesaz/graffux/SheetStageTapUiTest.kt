package com.hereliesaz.graffux

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * The sheet's stages (tab list, carousel, half grid, full grid) were reached only by swiping the sheet
 * up or down. Each step is now also a 48dp labelled button and an accessibility action, wired the way
 * MainActivity wires the sheet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class SheetStageTapUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var ui by mutableStateOf(CarouselUi(CarouselCategory.BRUSHES, sheetOpen = true, showingCarousel = false))

    private fun compose() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    CarouselSheet(
                        open = ui.sheetOpen,
                        onOpenChange = { ui = ui.copy(sheetOpen = it, showingCarousel = false) },
                        stages = SheetStages(
                            onStepUp = { ui = ui.steppedUp(gridAllowed = true) },
                            onStepDown = { ui.steppedDown(tabListStage = true)?.let { ui = it } != null },
                            canStepUp = { ui.steppedUp(gridAllowed = true) != ui },
                            canStepDown = { ui.steppedDown(tabListStage = true) != null },
                        ),
                    ) { Box(Modifier.fillMaxWidth().height(120.dp)) }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tap(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun stage() = Triple(ui.sheetOpen, ui.showingCarousel, ui.detent)

    @Test
    fun `tapping show more climbs the stages and the control is gone at the top`() {
        compose()
        assertEquals(Triple(true, false, CarouselDetent.SHORT), stage())
        tap("carousel.stage.up")
        assertEquals(Triple(true, true, CarouselDetent.SHORT), stage())
        tap("carousel.stage.up")
        assertEquals(CarouselDetent.HALF, ui.detent)
        tap("carousel.stage.up")
        assertEquals(CarouselDetent.FULL, ui.detent)
        val up = rule.onNodeWithTag("carousel.stage.up").fetchSemanticsNode().config
        assertNull("no tap at the top stage", up.getOrNull(SemanticsActions.OnClick))
    }

    @Test
    fun `tapping show less descends, and at the lowest stage the same control closes the sheet`() {
        compose()
        ui = ui.copy(showingCarousel = true, detent = CarouselDetent.FULL)
        rule.waitForIdle()
        tap("carousel.stage.down")
        assertEquals(CarouselDetent.HALF, ui.detent)
        tap("carousel.stage.down")
        assertEquals(CarouselDetent.SHORT, ui.detent)
        tap("carousel.stage.down")
        assertFalse("back at the tab list", ui.showingCarousel)
        val down = rule.onNodeWithTag("carousel.stage.down").fetchSemanticsNode().config
        assertEquals(listOf("Close sheet"), down.getOrNull(SemanticsProperties.ContentDescription))
        tap("carousel.stage.down")
        assertFalse("the lowest stage's control closes it", ui.sheetOpen)
    }

    @Test
    fun `the buttons are named and 48dp`() {
        compose()
        val min = 48 * rule.density.density
        listOf("carousel.stage.up" to "Show more", "carousel.stage.down" to "Close sheet").forEach { (tag, label) ->
            val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
            assertEquals(listOf(label), node.config.getOrNull(SemanticsProperties.ContentDescription))
            assertTrue(node.size.width >= min && node.size.height >= min)
        }
    }

    @Test
    fun `every step is also an accessibility action on the sheet`() {
        compose()
        fun sheetActions() = rule.onNode(hasStageActions(), true).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        fun labels() = sheetActions().map { it.label }
        assertEquals(listOf("Show more", "Close sheet"), labels())
        val action = sheetActions().first { it.label == "Show more" }
        rule.runOnUiThread { action.action() }
        rule.waitForIdle()
        assertTrue(ui.showingCarousel)
        assertEquals(listOf("Show more", "Show less", "Close sheet"), labels())
    }

    private fun hasStageActions() = androidx.compose.ui.test.SemanticsMatcher("has the stage actions") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Show more" } == true
    }
}
