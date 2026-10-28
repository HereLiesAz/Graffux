@file:Suppress("MaxLineLength", "MagicNumber")

package com.hereliesaz.graffux

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.photo.PhotoAdjustment
import com.hereliesaz.graffitixr.common.photo.PhotoSlider
import com.hereliesaz.graffitixr.common.photo.PhotoSliders
import com.hereliesaz.graffitixr.common.photo.PhotoTool
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
 * The photo sheet as MainActivity composes it (rails, logo, sheet), in the approved mockup's photo
 * boards (390 x 844): Adjust · enter (tab list), Light tab, and a slider mid-drag. Checks the flows
 * (a drag is one gesture, double-tap resets, hold compares, a tab opens its controls) and where
 * things sit. Set PHOTO_SCREENSHOTS to a directory to save each render; with the mockup's
 * background renders (bg-<Board>.png) in PHOTO_BACKGROUNDS the shots sit on the same photo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class PhotoSheetUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()
    private var adjustment by mutableStateOf<PhotoAdjustment?>(null)
    private val actions = object : PhotoActions {
        override fun gestureStart(tool: PhotoTool) {
            calls += "start:$tool"
            if (adjustment == null) adjustment = PhotoAdjustment(tool)
        }
        override fun slider(tool: PhotoTool, slider: PhotoSlider, value: Float) {
            calls += "slider:${slider.id}"
            adjustment = (adjustment ?: PhotoAdjustment(tool)).with(slider, value)
        }
        override fun gestureEnd() { calls += "end" }
        override fun resetSlider(tool: PhotoTool, slider: PhotoSlider) {
            calls += "reset:${slider.id}"
            adjustment = adjustment?.with(slider, slider.default)
        }
        override fun reset(tool: PhotoTool) { calls += "resetAll:$tool" }
        override fun compare(comparing: Boolean) { calls += "compare:$comparing" }
    }
    private val railActions = object : ToolRailActions {
        override fun setActiveTool(tool: Tool) = Unit
        override fun onColorClicked() = Unit
        override fun resetViewport() = Unit
        override fun onUndoClicked() = Unit
        override fun onRedoClicked() = Unit
        override fun setBrushSize(size: Float) = Unit
        override fun setBrushFeathering(feathering: Float) = Unit
    }

    private fun solid(argb: Int): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    private val drag = PhotoDrag()
    private var tab by mutableStateOf<PhotoTab?>(null)
    private var showing by mutableStateOf(false)

    /** The mockup's layers: the photo, the pink tag, and (once a slider moved) the Light layer. */
    private fun layers(): List<Layer> = listOfNotNull(
        Layer(id = "photo", name = "Photo", bitmap = solid(0xFF3C3C40.toInt())),
        Layer(id = "tag", name = "Tag", bitmap = solid(0xFFFF3EA5.toInt())),
        adjustment?.let { Layer(id = "light", name = "Light", adjustment = it) },
    )

    private fun compose(board: String) {
        val bg = System.getenv("PHOTO_BACKGROUNDS")?.let { File(it, "bg-$board.png") }?.takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0F))) {
                    if (bg != null) {
                        Image(bg.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselSheet(
                            open = true,
                            onOpenChange = {},
                            bottomInset = EdgeStripHeight + 4.dp,
                            chromeHidden = { drag.active },
                        ) {
                            PhotoSheetBody(
                                tab = tab,
                                showingControls = showing,
                                adjustment = adjustment,
                                actions = actions,
                                drag = drag,
                                onSelectTab = { tab = it; showing = true },
                            )
                        }
                    }
                    if (!drag.active) {
                        val state = EditorUiState(
                            undoCount = 1, layers = layers(), activeLayerId = if (adjustment != null) "light" else "photo",
                            brushSize = 24f, activeColor = Color(0xFFFF3EA5),
                        )
                        ToolRail(
                            vm = railActions,
                            uiState = state,
                            lit = setOf(PHOTO_RAIL_ID),
                            secondary = emptySet(),
                            callbacks = ToolRailCallbacks({}, {}, {}, onAdjust = { calls += "adjust" }),
                            modifier = Modifier.align(Alignment.TopStart).padding(start = RailSideInset, top = RailTopInset),
                        )
                        LayersRail(
                            layers = state.layers,
                            activeLayerId = state.activeLayerId,
                            secondary = emptySet(),
                            expansion = emptyMap(),
                            callbacks = LayersRailCallbacks({}, {}, { _, _ -> }, { _, _ -> }, {}),
                            modifier = Modifier.align(Alignment.TopEnd).padding(end = RailSideInset, top = LayersRailTopInset),
                        )
                        LogoMenu(open = false, onOpenChange = {}, rows = buildMenu { listItem("Settings") {} }, modifier = Modifier.align(Alignment.TopEnd))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun save(name: String) {
        val dir = System.getenv("PHOTO_SCREENSHOTS") ?: return
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun dp(px: Float) = Math.round(px / rule.density.density)

    @Test
    fun `entry rests at the photo tab list and a tab opens its sliders`() {
        compose("PhotoEntry")
        save("app-PhotoEntry")
        rule.onNodeWithTag("rail.$PHOTO_RAIL_ID").performClick()
        assertEquals(listOf("adjust"), calls)
        // The tab list is the brush sheet's own, with the photo tabs.
        rule.onAllNodesWithTag("carousel.tab.photo.light").onFirst().assertExists()
        rule.onAllNodesWithTag("carousel.tab.photo.color").onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("photo.title").assertTextEquals("Color")
        rule.onNodeWithTag("photo.slider.temperature").assertExists()
        rule.onNodeWithTag("photo.value.temperature").assertTextEquals("6500 K")
    }

    @Test
    fun `the Light tab sits where the mockup has it`() {
        tab = PhotoTab.LIGHT
        showing = true
        adjustment = PhotoAdjustment(PhotoTool.LIGHT)
            .with(PhotoSliders.EXPOSURE, 0.35f).with(PhotoSliders.CONTRAST, 12f).with(PhotoSliders.HIGHLIGHTS, -38f)
            .with(PhotoSliders.SHADOWS, 41f).with(PhotoSliders.WHITES, 8f).with(PhotoSliders.BLACKS, -14f)
        compose("PhotoLight")
        save("app-PhotoLight")
        rule.onNodeWithTag("photo.value.exposure").assertTextEquals("+0.35")
        rule.onNodeWithTag("photo.value.highlights").assertTextEquals("−38")
        // Rows: 44 tall, the first one 96 below the sheet's top (the pill strip 48 + header 44 + 4).
        val panel = rule.onNodeWithTag("photo.panel.photo.light").fetchSemanticsNode().boundsInRoot
        val exposure = rule.onNodeWithTag("photo.slider.exposure").fetchSemanticsNode().boundsInRoot
        assertEquals(44, dp(exposure.height))
        assertEquals(48, dp(exposure.top - panel.top))
        // The tab list's bottom edge: the mockup's 828, plus the sheet's own 4 over the edge strip.
        val tabs = rule.onNodeWithTag("carousel.tabs").fetchSemanticsNode().boundsInRoot
        assertEquals(844 - 20, dp(tabs.bottom))
        assertEquals(344 - 48, dp(tabs.top - panel.top))
        // The tool rail gains Adjust after Transform (the Eraser the app keeps follows it).
        val tools = rule.onNodeWithTag("rail.tools").fetchSemanticsNode().boundsInRoot
        assertEquals(488 + 50 + 50, dp(tools.height))
    }

    @Test
    fun `a drag is one gesture, hides the rest and shows its live value`() {
        tab = PhotoTab.LIGHT
        showing = true
        compose("PhotoDrag")
        val row = rule.onNodeWithTag("photo.slider.exposure")
        row.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(40f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(40f, 0f))
        }
        rule.waitForIdle()
        assertTrue(drag.active)
        rule.onNodeWithTag("rail.tools").assertDoesNotExist()
        save("app-PhotoDrag")
        row.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("start:LIGHT", calls.first())
        assertEquals("end", calls.last())
        assertTrue(calls.drop(1).dropLast(1).all { it == "slider:exposure" })
        assertTrue((adjustment?.value(PhotoSliders.EXPOSURE) ?: 0f) > 0f)
        rule.onNodeWithTag("rail.tools").assertExists()
    }

    @Test
    fun `double-tap resets a slider and holding compare compares`() {
        tab = PhotoTab.LIGHT
        showing = true
        adjustment = PhotoAdjustment(PhotoTool.LIGHT).with(PhotoSliders.CONTRAST, 30f)
        compose("PhotoLight")
        rule.onNodeWithTag("photo.slider.contrast").performTouchInput {
            click(center)
            advanceEventTime(100)
            click(center)
        }
        rule.waitForIdle()
        assertTrue(calls.contains("reset:contrast"))
        rule.onNodeWithTag("photo.compare").performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals("compare:true", calls.last())
        rule.onNodeWithTag("photo.compare").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("compare:false", calls.last())
        rule.onNodeWithTag("photo.reset").performClick()
        assertEquals("resetAll:LIGHT", calls.last())
    }
}
