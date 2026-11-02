package com.hereliesaz.graffux

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.azphalt.BuiltInBrushes
import com.hereliesaz.graffitixr.common.model.EditorUiState
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
import java.io.File
import kotlin.math.abs

/** The brush pack wheel on the tool rail (RailPackWheel.kt), as MainActivity wires it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi", application = Application::class)
class RailPackWheelUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var tool by mutableStateOf(Tool.BRUSH)
    private var ui by mutableStateOf(CarouselUi(CarouselCategory.BRUSHES, sheetOpen = false, showingCarousel = false))
    private var stroke by mutableStateOf(false)
    private val wheel = PackWheelState()
    private val picks = mutableListOf<String>()
    private var underlayTaps = 0

    private val actions = object : ToolRailActions {
        override fun setActiveTool(tool: Tool) { this@RailPackWheelUiTest.tool = tool }
        override fun onColorClicked() = Unit
        override fun resetViewport() = Unit
        override fun onUndoClicked() = Unit
        override fun onRedoClicked() = Unit
        override fun setBrushSize(size: Float) = Unit
        override fun setBrushFeathering(feathering: Float) = Unit
    }

    private val inputs = CarouselInputs(
        activeTool = Tool.BRUSH, activeBrushName = BuiltInBrushes.presets[0].name,
        builtInBrushes = BuiltInBrushes.presets,
        customBrushes = listOf("c1" to BuiltInBrushes.presets[1].copy(name = "Mine")),
        extensionBrushes = listOf("caps::0" to "Fat Cap", "caps::1" to "Skinny"),
        stabilizerLevel = 0, stabilizerAlgorithm = StabilizerAlgorithm.STREAMLINE,
        smudgeMode = ColorSmudgeEngine.Mode.DULLING, selectionShape = SelectionShape.ELLIPSE,
    )
    private val sections = carouselTabSections(
        CarouselContext.BRUSHES, CarouselCategory.BRUSHES, inputs, packNames = mapOf("caps" to "Spray Caps"),
    )
    private val sets = carouselSets(sections)
    private val labels = carouselSetLabels(sections)

    private fun compose(rightHanded: Boolean = true, start: Tool = Tool.BRUSH) {
        tool = start
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF202024)).closesPackWheelOutside(wheel)) {
                    // Stands in for the canvas: whatever the wheel lets through lands here.
                    Box(Modifier.fillMaxSize().testTag("underlay").clickable { underlayTaps++ })
                    ToolRail(
                        vm = actions,
                        uiState = EditorUiState(activeTool = tool),
                        lit = setOfNotNull(TOOL_CATALOG[tool]?.id),
                        secondary = emptySet(),
                        callbacks = ToolRailCallbacks(
                            onToolOptions = {}, onToolGroup = {}, onBrushMenu = {},
                            onBrush = { ui = ui.openOnBrushes() },
                            onPackWheelToggle = wheel::toggle,
                            onPackWheelOpen = { wheel.open = true },
                            onPackAnchor = wheel::anchor,
                        ),
                        modifier = Modifier
                            .align(if (rightHanded) Alignment.TopStart else Alignment.TopEnd)
                            .padding(horizontal = RailSideInset)
                            .padding(top = RailTopInset, bottom = RailWidth + RailEdgeGap * 2),
                    )
                    // Where the layers rail sits: the wheel must keep clear of it.
                    Box(
                        Modifier
                            .align(if (rightHanded) Alignment.TopEnd else Alignment.TopStart)
                            .padding(horizontal = RailSideInset)
                            .padding(top = LayersRailTopInset)
                            .width(RailWidth)
                            .height(420.dp)
                            .background(Color(0xFF34343A))
                            .testTag("layers"),
                    )
                    RailPackWheel(
                        state = wheel,
                        sets = sets,
                        labels = labels,
                        selectedKey = selectedTabGroup(sections, ui.tab)?.key,
                        opensRight = rightHanded,
                        onPick = { group ->
                            picks += group.key
                            ui = ui.copy(sheetOpen = true, photo = false).withSet(group)
                        },
                        closeWhen = { stroke },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun wheelShown() = rule.onAllNodesWithTag("rail.packwheel").fetchSemanticsNodes().isNotEmpty()
    private fun items(): List<SemanticsNode> =
        rule.onAllNodesWithTag("rail.packwheel.item", useUnmergedTree = true).fetchSemanticsNodes()
    private fun SemanticsNode.label() = config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
    private fun SemanticsNode.isSelected() = config.getOrNull(SemanticsProperties.Selected) == true
    private fun anchorY() = (bounds("rail.tool.brush").center.y + bounds("rail.tool.eraser").center.y) / 2
    private fun px(dp: Float) = dp * rule.density.density

    private fun openWheel() {
        rule.onNodeWithTag("rail.tool.brush").performClick()
        rule.waitForIdle()
        assertTrue("wheel open", wheelShown())
    }

    /** A point on the disc, [deg] round from the pointer (positive: below it), at a name's middle. */
    private fun onSpoke(deg: Float, rightHanded: Boolean = true): Offset {
        val w = bounds("rail.packwheel")
        val r = w.height / 2
        val hub = Offset(if (rightHanded) 0f else w.width, r)
        val a = Math.toRadians((packPointerDegrees(rightHanded) + (if (rightHanded) deg else -deg)).toDouble())
        val d = r * 0.62f
        return hub + Offset((d * kotlin.math.cos(a)).toFloat(), (d * kotlin.math.sin(a)).toFloat())
    }

    @Test
    fun `the eraser sits directly below the brush`() {
        compose()
        val brush = bounds("rail.tool.brush")
        val eraser = bounds("rail.tool.eraser")
        assertEquals(brush.center.x, eraser.center.x, 1f)
        assertTrue("eraser below brush", eraser.top >= brush.bottom - 1f)
        assertTrue("nothing between them (gap ${eraser.top - brush.bottom})", eraser.top - brush.bottom < px(12f))
        // And the rest keeps its order: Fill follows the pair.
        assertTrue(bounds("rail.tool.fill").top >= eraser.bottom - 1f)
    }

    @Test
    fun `tapping the active brush opens the wheel and tapping again shuts it`() {
        compose()
        openWheel()
        rule.onNodeWithTag("rail.tool.brush").performClick()
        rule.waitForIdle()
        assertFalse(wheelShown())
        assertEquals(Tool.BRUSH, tool)
    }

    @Test
    fun `picking the brush up from another tool opens the sheet, not the wheel`() {
        compose(start = Tool.FILL)
        rule.onNodeWithTag("rail.tool.brush").performClick()
        rule.waitForIdle()
        assertEquals(Tool.BRUSH, tool)
        assertFalse(wheelShown())
        assertTrue(ui.sheetOpen)
    }

    @Test
    fun `a long-press on the brush opens the wheel`() {
        compose(start = Tool.FILL)
        rule.onNodeWithTag("rail.tool.brush").performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue(wheelShown())
        assertEquals(Tool.BRUSH, tool)
    }

    @Test
    fun `the pointer is horizontal at the centre and the active pack is on it`() {
        ui = ui.copy(tab = "studio")
        compose()
        openWheel()
        rule.mainClock.advanceTimeBy(500)
        val wheelBox = bounds("rail.packwheel")
        // The disc's hub is on the rail edge, centred on the Brush and Eraser pair.
        assertEquals(anchorY(), wheelBox.center.y, 2f)
        val active = items().single { it.isSelected() }
        assertEquals("My Brushes", active.label())
        assertEquals("on the horizontal spoke", anchorY(), active.boundsInRoot.center.y, 2f)
        assertTrue("out from the rail", active.boundsInRoot.center.x > bounds("rail.tool.brush").right)
    }

    @Test
    fun `favorites is the first entry`() {
        compose()
        openWheel()
        assertEquals("Favorites", items().first().label())
        assertEquals(FAVORITES_SET_KEY, sets.first().key)
    }

    @Test
    fun `a tap on a name commits it at once and the wheel shuts after`() {
        ui = ui.copy(tab = sets[1].key)
        compose()
        openWheel()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("rail.packwheel").performTouchInput { click(onSpoke(PACK_WEDGE_DEGREES)) }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(listOf(sets[2].key), picks)
        assertEquals(sets[2].key, ui.tab)
        assertTrue(ui.sheetOpen)
        assertTrue(wheelShown())
        rule.mainClock.advanceTimeBy(1_500)
        assertFalse("shut ${PACK_WHEEL_CLOSE_MS}ms after the commit", wheelShown())
    }

    @Test
    fun `a drag commits only after the dwell`() {
        ui = ui.copy(tab = sets[1].key)
        compose()
        openWheel()
        rule.mainClock.autoAdvance = false
        // Up past the pointer: the names follow, so the next pack below comes to the pointer.
        rule.onNodeWithTag("rail.packwheel").performTouchInput {
            swipe(onSpoke(PACK_WEDGE_DEGREES * 1.5f), onSpoke(PACK_WEDGE_DEGREES * 0.5f), 400)
        }
        rule.mainClock.advanceTimeBy(600)
        assertTrue("provisional while it settles", picks.isEmpty())
        rule.mainClock.advanceTimeBy(WHEEL_DWELL_MS)
        assertEquals(listOf(sets[2].key), picks)
        rule.mainClock.advanceTimeBy(PACK_WHEEL_CLOSE_MS + 100)
        assertFalse(wheelShown())
    }

    @Test
    fun `left-handed it mirrors onto the rail's left side`() {
        ui = ui.copy(tab = "studio")
        compose(rightHanded = false)
        openWheel()
        val brush = bounds("rail.tool.brush")
        val wheelBox = bounds("rail.packwheel")
        assertTrue("opens left of the rail", wheelBox.right <= brush.left + 1f)
        val active = items().single { it.isSelected() }
        assertEquals(anchorY(), active.boundsInRoot.center.y, 2f)
        assertTrue(active.boundsInRoot.center.x < brush.left)
        // Later packs lie below the pointer on either hand.
        val next = items()[sets.indexOfFirst { it.key == "studio" } + 1]
        assertTrue(next.boundsInRoot.center.y > active.boundsInRoot.center.y)
        rule.onNodeWithTag("rail.packwheel").performTouchInput {
            click(onSpoke(PACK_WEDGE_DEGREES, rightHanded = false))
        }
        rule.waitForIdle()
        assertEquals(sets[sets.indexOfFirst { it.key == "studio" } + 1].key, picks.first())
    }

    @Test
    fun `it shuts when a stroke starts`() {
        compose()
        openWheel()
        stroke = true
        rule.waitForIdle()
        assertFalse(wheelShown())
    }

    @Test
    fun `a press off the disc shuts it, and the disc's corners let touches through`() {
        compose()
        openWheel()
        val box = bounds("rail.packwheel")
        // The box's far top corner is off the half disc.
        rule.onNodeWithTag("underlay").performTouchInput { click(Offset(box.right - 4f, box.top + 4f)) }
        rule.waitForIdle()
        assertEquals("the corner reached the canvas", 1, underlayTaps)
        assertFalse(wheelShown())
    }

    @Test
    fun `with the eraser in hand the wheel picks the shared pack`() {
        ui = ui.copy(tab = sets[1].key)
        compose(start = Tool.ERASER)
        rule.onNodeWithTag("rail.tool.eraser").performClick()
        rule.waitForIdle()
        assertEquals("the second tap does not put the eraser down", Tool.ERASER, tool)
        assertTrue(wheelShown())
        rule.onNodeWithTag("rail.packwheel").performTouchInput { click(onSpoke(PACK_WEDGE_DEGREES)) }
        rule.waitForIdle()
        assertEquals(sets[2].key, ui.tab)
        assertEquals(Tool.ERASER, tool)
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun `it fits a 360dp phone, clear of the layers rail, with full names`() {
        compose()
        openWheel()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val box = bounds("rail.packwheel")
        assertTrue("inside the screen $box", box.left >= root.left && box.right <= root.right)
        assertTrue("inside the screen $box", box.top >= 0f && box.bottom <= root.bottom)
        assertTrue("clear of the layers rail", box.right <= bounds("layers").left)
        val radiusDp = box.height / 2 / rule.density.density
        assertTrue("radius $radiusDp", radiusDp >= PackWheelMinRadius.value - 0.5f)
        listOf("My Brushes", "Spray Caps", "Favorites").forEach { name ->
            val node = items().single { it.label() == name }
            assertFalse("$name is not cut", node.config[PackNameEllipsized])
        }
    }

    @Test
    fun `semantics name the packs, the selected one, and step next and previous`() {
        ui = ui.copy(tab = sets[1].key)
        compose()
        openWheel()
        val node = rule.onNodeWithTag("rail.packwheel").fetchSemanticsNode()
        assertEquals(listOf("Brush packs"), node.config[SemanticsProperties.ContentDescription])
        val custom = node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Next pack", "Previous pack"), custom.map { it.label })
        rule.runOnUiThread { custom[0].action() }
        rule.waitForIdle()
        assertEquals(sets[2].key, picks.last())
        rule.runOnUiThread { custom[1].action() }
        rule.waitForIdle()
        assertEquals(sets[1].key, picks.last())
        assertTrue("stays up while stepping", wheelShown())
        assertEquals(sets.size, items().size)
        assertEquals(sets.map { labels.getValue(it.key) }, items().map { it.label() })
        val third = items()[3]
        rule.runOnUiThread { third.config[SemanticsActions.OnClick].action?.invoke() }
        rule.waitForIdle()
        assertEquals(sets[3].key, picks.last())
    }

    /** Pure geometry: the radius gives way before the hub moves, and the hub never leaves the area. */
    @Test
    fun `layout shrinks to fit then slides`() {
        val l = packWheelLayout(
            androidx.compose.ui.geometry.Size(1000f, 2000f), 100f, 120f, true, 400f, 300f, 200f, 20f,
        )
        assertEquals(300f, l.radius, 0.1f)
        assertEquals(320f, l.hub.y, 0.1f)
        val roomy = packWheelLayout(
            androidx.compose.ui.geometry.Size(1000f, 2000f), 100f, 1000f, true, 400f, 300f, 200f, 20f,
        )
        assertEquals(400f, roomy.radius, 0.1f)
        assertEquals(1000f, roomy.hub.y, 0.1f)
        assertTrue(abs(packTextRotation(packSpokeDegrees(1, 0f, false), false)) < 90f)
    }

    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File("build/packwheel-shots").mkdirs()
        File("build/packwheel-shots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `render right-handed`() {
        ui = ui.copy(tab = "studio")
        compose()
        openWheel()
        shot("right-handed")
    }

    @Test
    fun `render left-handed`() {
        ui = ui.copy(tab = "studio")
        compose(rightHanded = false)
        openWheel()
        shot("left-handed")
    }
}
