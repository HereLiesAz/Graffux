@file:Suppress("MaxLineLength", "MagicNumber", "LongParameterList")

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.photo.AdjustmentMask
import com.hereliesaz.graffitixr.common.photo.HealSpot
import com.hereliesaz.graffitixr.common.photo.PhotoAdjustment
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
 * The RETOUCH tabs and the layers rail's adjustment tiles, in the mockup's PhotoHeal, PhotoMask and
 * PhotoLayers boards: Heal's spots and source rings, Local's brush, and the tiles' glyphs, mask
 * badge and hidden badge. Set PHOTO_SCREENSHOTS / PHOTO_BACKGROUNDS as in PhotoSheetUiTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class PhotoRetouchUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()
    private val actions = object : RetouchActions {
        override fun healSpot(x: Float, y: Float, radius: Float, feather: Float, opacity: Float, clone: Boolean) { calls += "heal:${x.toInt()},${y.toInt()},${radius.toInt()},$clone" }
        override fun healSourceStart() { calls += "srcStart" }
        override fun moveHealSource(index: Int, x: Float, y: Float) { calls += "src:$index" }
        override fun healSourceEnd() { calls += "srcEnd" }
        override fun setHealClone(clone: Boolean) { calls += "clone:$clone" }
        override fun redEye(x: Float, y: Float, radius: Float) { calls += "redEye:${x.toInt()},${y.toInt()}" }
        override fun maskStart(x: Float, y: Float, radius: Float, feather: Float, flow: Float, erase: Boolean) { calls += "maskStart:$erase:${radius.toInt()}" }
        override fun maskTo(x: Float, y: Float, radius: Float, feather: Float, flow: Float, erase: Boolean) { calls += "maskTo" }
        override fun maskEnd() { calls += "maskEnd" }
        override fun maskFromSelection() { calls += "fromSelection" }
        override fun maskInvert() { calls += "invert" }
        override fun maskReset() { calls += "maskReset" }
        override fun maskOverlay(on: Boolean) { calls += "overlay:$on" }
    }
    private val photoActions = object : PhotoActions {
        override fun gestureStart(tool: PhotoTool) = Unit
        override fun slider(tool: PhotoTool, slider: com.hereliesaz.graffitixr.common.photo.PhotoSlider, value: Float) = Unit
        override fun gestureEnd() = Unit
        override fun resetSlider(tool: PhotoTool, slider: com.hereliesaz.graffitixr.common.photo.PhotoSlider) = Unit
        override fun reset(tool: PhotoTool) = Unit
        override fun compare(comparing: Boolean) = Unit
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
    private val state = RetouchState()
    private val drag = PhotoDrag()
    private val camera = CanvasCamera(1f, Offset.Zero, 0f)

    private fun solid(argb: Int): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    /** A blob mask like the mockup's, for the badge and the overlay. */
    private fun blobMask(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { b ->
        android.graphics.Canvas(b).drawOval(w * 0.18f, h * 0.4f, w * 0.75f, h * 0.57f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
    }

    private fun compose(board: String, tab: PhotoTab?, layers: List<Layer>, active: String?, overlay: @androidx.compose.runtime.Composable () -> Unit = {}, thumbs: Map<String, Bitmap> = emptyMap(), maskTarget: String? = null) {
        val bg = System.getenv("PHOTO_BACKGROUNDS")?.let { File(it, "bg-$board.png") }?.takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0F))) {
                    if (bg != null) Image(bg.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    overlay()
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselSheet(open = true, onOpenChange = {}, bottomInset = EdgeStripHeight + 4.dp) {
                            PhotoSheetBody(
                                tab = tab, showingControls = tab != null, adjustment = null, actions = photoActions, drag = drag,
                                onSelectTab = {}, retouch = RetouchContext(state, actions, maskTarget),
                            )
                        }
                    }
                    val ui = EditorUiState(undoCount = 1, layers = layers, activeLayerId = active, brushSize = 24f, activeColor = Color(0xFFFF3EA5))
                    ToolRail(
                        vm = railActions, uiState = ui, lit = if (tab != null) setOf(PHOTO_RAIL_ID) else emptySet(), secondary = emptySet(),
                        callbacks = ToolRailCallbacks({}, {}, {}),
                        modifier = Modifier.align(Alignment.TopStart).padding(start = RailSideInset, top = RailTopInset),
                    )
                    LayersRail(
                        layers = layers, activeLayerId = active, secondary = emptySet(), expansion = emptyMap(),
                        callbacks = LayersRailCallbacks({}, {}, { _, _ -> }, { _, _ -> }, {}),
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = RailSideInset, top = LayersRailTopInset),
                        maskThumbs = thumbs,
                    )
                    LogoMenu(open = false, onOpenChange = {}, rows = buildMenu { listItem("Settings") {} }, modifier = Modifier.align(Alignment.TopEnd))
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

    private val photo = Layer(id = "photo", name = "Photo", bitmap = solid(0xFF3C3C40.toInt()))
    private val tag = Layer(id = "tag", name = "Tag", bitmap = solid(0xFFFF3EA5.toInt()))
    private val light = Layer(id = "light", name = "Light", adjustment = PhotoAdjustment(PhotoTool.LIGHT, mask = AdjustmentMask(enabled = true)))

    @Test
    fun `heal shows its spots and a tap heals where it lands`() {
        // The board's spot: target (150, 250), source (206, 232), radius 18 (screen = world at zoom 1, 2x density).
        val d = 2f
        val spots = listOf(HealSpot(150f * d, 250f * d, 18f * d, 206f * d, 232f * d))
        val heal = Layer(id = "heal", name = "Heal", adjustment = PhotoAdjustment(PhotoTool.HEAL, healSpots = spots))
        compose("PhotoHeal", PhotoTab.HEAL, listOf(photo, tag, light.copy(adjustment = PhotoAdjustment(PhotoTool.LIGHT)), heal), "heal", overlay = { HealOverlay(spots, camera, RetouchContext(state, actions, null)) })
        save("app-PhotoHeal")
        rule.onNodeWithTag("photo.heal.overlay").performTouchInput { down(Offset(300f * d, 300f * d)); up() }
        rule.waitForIdle()
        // Size 48dp is a 24dp radius: 48px at 2x.
        assertEquals("heal:600,600,48,false", calls.last())
        rule.onNodeWithTag("photo.heal.overlay").performTouchInput {
            down(Offset(206f * d, 232f * d)); moveBy(Offset(20f, 0f)); up()
        }
        rule.waitForIdle()
        assertEquals("srcStart", calls[1])
        assertEquals("srcEnd", calls.last())
        rule.onNodeWithTag("photo.heal.clone").performClick()
        assertEquals("clone:true", calls.last())
    }

    @Test
    fun `local brushes the mask, with the mask badge on its layer`() {
        val d = 2f
        val mask = blobMask(780, 1688)
        val thumb = Bitmap.createScaledBitmap(mask, 48, 104, true)
        state.overlay = true
        compose(
            "PhotoMask", PhotoTab.LOCAL, listOf(photo, tag, light), "light",
            overlay = { LocalMaskOverlay(mask, camera, RetouchContext(state, actions, "Light")) },
            thumbs = mapOf("light" to thumb), maskTarget = "Light",
        )
        rule.onNodeWithTag("photo.title").assertTextEquals("Local · mask on Light")
        rule.onNodeWithTag("rail.mask.light").assertExists()
        rule.onNodeWithTag("photo.mask.canvas").performTouchInput { down(Offset(262f * d, 372f * d)) }
        rule.waitForIdle()
        save("app-PhotoMask")
        rule.onNodeWithTag("photo.mask.canvas").performTouchInput { moveBy(Offset(30f, 10f)); up() }
        rule.waitForIdle()
        // Size 64dp: a 32dp radius, 64px at 2x.
        assertEquals("maskStart:false:64", calls.first())
        assertTrue(calls.contains("maskTo"))
        assertEquals("maskEnd", calls.last())
        rule.onNodeWithTag("photo.mask.erase").performClick()
        rule.onNodeWithTag("photo.mask.canvas").performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertTrue(calls.any { it.startsWith("maskStart:true") })
        rule.onNodeWithTag("photo.mask.selection").performClick()
        rule.onNodeWithTag("photo.mask.invert").performClick()
        rule.onNodeWithTag("photo.mask.overlay").performClick()
        rule.onNodeWithTag("photo.reset").performClick()
        assertEquals(listOf("fromSelection", "invert", "overlay:false", "maskReset"), calls.takeLast(4))
    }

    @Test
    fun `red-eye takes a tap per eye`() {
        compose("PhotoHeal", PhotoTab.RED_EYE, listOf(photo), "photo", overlay = { RedEyeOverlay(emptyList(), camera, RetouchContext(state, actions, null)) })
        rule.onNodeWithTag("photo.redeye.overlay").performTouchInput { down(Offset(400f, 600f)); up() }
        assertEquals("redEye:400,600", calls.last())
    }

    @Test
    fun `adjustment tiles show their glyphs, a mask badge and the hidden badge`() {
        val heal = Layer(id = "heal", name = "Heal", adjustment = PhotoAdjustment(PhotoTool.HEAL))
        val curves = Layer(id = "curves", name = "Curves", adjustment = PhotoAdjustment(PhotoTool.CURVES), isVisible = false)
        // Bottom-first: photo, curves (hidden), tag, light (masked, selected), heal.
        val layers = listOf(photo, curves, tag, light, heal)
        compose("PhotoLayers", null, layers, "light", thumbs = mapOf("light" to Bitmap.createScaledBitmap(blobMask(780, 1688), 48, 104, true)))
        save("app-PhotoLayers")
        rule.onNodeWithTag("rail.mask.light").assertExists()
        rule.onNodeWithTag("rail.mask.curves").assertDoesNotExist()
    }
}
