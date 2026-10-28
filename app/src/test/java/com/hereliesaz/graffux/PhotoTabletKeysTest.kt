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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The photo sheet on a tablet (the mockup's PhotoTablet board, 1180 x 820): the slider column
 * 390 wide, centred, on the Color tab. And the keyboard: the arrow keys nudge the focused slider
 * (Shift ten times as far), "A" opens Adjust, "\\" held compares. PHOTO_SCREENSHOTS saves the shot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1180dp-h820dp-xhdpi", application = Application::class)
class PhotoTabletKeysTest {

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
        override fun update(tool: PhotoTool, change: (PhotoAdjustment) -> PhotoAdjustment) {
            calls += "update:$tool"
            adjustment = change(adjustment ?: PhotoAdjustment(tool))
        }
        override fun eyedropper(armed: Boolean) { calls += "eyedropper:$armed" }
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
    private val sheet = PhotoSheetState()
    private var tab by mutableStateOf<PhotoTab?>(null)
    private var showing by mutableStateOf(false)

    /** The mockup's layers: the photo, the pink tag, and (once a slider moved) the Light layer. */
    private fun layers(): List<Layer> = listOfNotNull(
        Layer(id = "photo", name = "Photo", bitmap = solid(0xFF3C3C40.toInt())),
        Layer(id = "tag", name = "Tag", bitmap = solid(0xFFFF3EA5.toInt())),
        adjustment?.let { Layer(id = "light", name = it.tool.label, adjustment = it) },
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
                                state = sheet,
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
    fun `the tablet's Color tab is a 390 column in the middle`() {
        tab = PhotoTab.COLOR
        showing = true
        adjustment = PhotoAdjustment(PhotoTool.COLOR)
            .with(PhotoSliders.TEMPERATURE, 5900f).with(PhotoSliders.TINT, 4f)
            .with(PhotoSliders.VIBRANCE, 18f).with(PhotoSliders.SATURATION, -6f)
        compose("PhotoTablet")
        save("app-PhotoTablet")
        rule.onNodeWithTag("photo.value.temperature").assertTextEquals("5900 K")
        val panel = rule.onNodeWithTag("photo.panel.photo.color").fetchSemanticsNode().boundsInRoot
        assertEquals(390, dp(panel.width))
        assertEquals(1180 / 2, dp(panel.left + panel.width / 2))
    }

    @Test
    fun `arrow keys nudge the focused slider, Shift ten times as far, each one undo step`() {
        tab = PhotoTab.LIGHT
        showing = true
        adjustment = PhotoAdjustment(PhotoTool.LIGHT)
        compose("PhotoTablet")
        val row = rule.onNodeWithTag("photo.slider.contrast")
        row.performTouchInput { click(center) }
        rule.waitForIdle()
        calls.clear()
        row.performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(listOf("start:LIGHT", "slider:contrast", "end"), calls)
        assertEquals(2f, adjustment!!.value(PhotoSliders.CONTRAST), 1e-3f)
        row.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionLeft) } }
        rule.waitForIdle()
        assertEquals(-18f, adjustment!!.value(PhotoSliders.CONTRAST), 1e-3f)
    }

    @Test
    fun `A opens Adjust and backslash held compares`() {
        var opened = 0
        val seen = mutableListOf<Boolean>()
        fun key(k: Key, type: Int, photo: Boolean) = photoShortcut(
            KeyEvent(android.view.KeyEvent(type, k.nativeKeyCode)), photo, { opened++ }, { seen += it },
        )
        assertTrue(key(Key.A, android.view.KeyEvent.ACTION_DOWN, false))
        assertFalse(key(Key.A, android.view.KeyEvent.ACTION_UP, false))
        assertEquals(1, opened)
        assertFalse(key(Key.Backslash, android.view.KeyEvent.ACTION_DOWN, false))
        assertTrue(key(Key.Backslash, android.view.KeyEvent.ACTION_DOWN, true))
        assertTrue(key(Key.Backslash, android.view.KeyEvent.ACTION_UP, true))
        assertEquals(listOf(true, false), seen)
    }
}
