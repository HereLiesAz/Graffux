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
import com.hereliesaz.graffitixr.common.photo.DocumentCrop
import com.hereliesaz.graffitixr.feature.editor.documentCrop
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
 * The Crop tab and the crop frame, as the mockup's "Crop in progress" board draws them: a 4:5
 * crop on a straightened, perspective-corrected photo, a corner held (so the thirds show). The
 * frame drags and keeps its aspect, chips/rotate/flip/auto level each make one change, and a
 * slider sets the geometry. Set PHOTO_SCREENSHOTS / PHOTO_BACKGROUNDS as in PhotoSheetUiTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi", application = Application::class)
class PhotoCropUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    // The board's frame: (79, 98)-(311, 388) on a 390 x 844 screen, the document being the screen.
    private var crop by mutableStateOf(
        DocumentCrop(left = 79f / 390f, top = 98f / 844f, right = 311f / 390f, bottom = 388f / 844f, aspect = "4:5", straighten = -3f, vertical = 18f),
    )
    private val calls = mutableListOf<String>()
    private val actions = object : CropActions {
        override fun gestureStart() { calls += "start" }
        override fun set(crop: DocumentCrop) { calls += "set"; this@PhotoCropUiTest.crop = crop }
        override fun gestureEnd() { calls += "end" }
        override fun apply(change: (DocumentCrop) -> DocumentCrop) { calls += "apply"; crop = change(crop) }
        override fun autoLevel() { calls += "autoLevel" }
    }
    private val photoActions = object : PhotoActions {
        override fun gestureStart(tool: com.hereliesaz.graffitixr.common.photo.PhotoTool) = Unit
        override fun slider(tool: com.hereliesaz.graffitixr.common.photo.PhotoTool, slider: com.hereliesaz.graffitixr.common.photo.PhotoSlider, value: Float) = Unit
        override fun gestureEnd() = Unit
        override fun resetSlider(tool: com.hereliesaz.graffitixr.common.photo.PhotoTool, slider: com.hereliesaz.graffitixr.common.photo.PhotoSlider) = Unit
        override fun reset(tool: com.hereliesaz.graffitixr.common.photo.PhotoTool) = Unit
        override fun compare(comparing: Boolean) = Unit
    }
    private val drag = PhotoDrag()
    private val docW = 780
    private val docH = 1688

    private fun compose() {
        val bg = System.getenv("PHOTO_BACKGROUNDS")?.let { File(it, "bg-PhotoLight.png") }?.takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E0E0F))) {
                    // The photo, through the crop's transform, whole (the tab is open), as EditorScreen draws it.
                    Box(Modifier.fillMaxSize().documentCrop(crop, editing = true, docW, docH)) {
                        if (bg != null) Image(bg.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                    CropOverlay(crop, docW, docH, zoom = 1f, offset = Offset.Zero, rotation = 0f, actions = actions)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CarouselSheet(open = true, onOpenChange = {}, bottomInset = CarouselBottomInset, chromeHidden = { drag.active }) {
                            PhotoSheetBody(
                                tab = PhotoTab.CROP, showingControls = true, adjustment = null, actions = photoActions,
                                drag = drag, onSelectTab = {}, crop = CropContext(crop, docW, docH, actions),
                            )
                        }
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

    @Test
    fun `the crop tab and its frame, a corner held`() {
        compose()
        rule.onNodeWithTag("photo.title").assertTextEquals("Crop · 4:5")
        rule.onNodeWithTag("photo.value.crop.straighten").assertTextEquals("−3.0°")
        rule.onNodeWithTag("photo.value.crop.vertical").assertTextEquals("+18")
        val d = rule.density.density
        rule.onNodeWithTag("photo.crop.overlay").performTouchInput { down(Offset(311f * d, 388f * d)) }
        rule.waitForIdle()
        save("app-PhotoCrop")
        rule.onNodeWithTag("photo.crop.overlay").performTouchInput {
            moveBy(Offset(-40f * d, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals("start", calls.first())
        assertEquals("end", calls.last())
        // The bottom-right corner moved 40 left: the width shrank and the 4:5 height followed.
        assertEquals((311f - 40f) / 390f, crop.right, 1e-3f)
        val w = (crop.right - crop.left) * docW
        val h = (crop.bottom - crop.top) * docH
        assertEquals(4f / 5f, w / h, 1e-3f)
    }

    @Test
    fun `chips, rotate, flips, auto level and reset each make one change`() {
        compose()
        rule.onNodeWithTag("photo.aspect.1:1").performClick()
        rule.waitForIdle()
        assertEquals("1:1", crop.aspect)
        val w = (crop.right - crop.left) * docW
        assertEquals(w, (crop.bottom - crop.top) * docH, 1f)
        rule.onNodeWithTag("photo.crop.rotate").performClick()
        rule.onNodeWithTag("photo.crop.fliph").performClick()
        rule.onNodeWithTag("photo.crop.flipv").performClick()
        rule.waitForIdle()
        assertEquals(1, crop.quarterTurns)
        assertTrue(crop.flipH && crop.flipV)
        rule.onNodeWithTag("photo.crop.autolevel").performClick()
        assertEquals("autoLevel", calls.last())
        rule.onNodeWithTag("photo.reset").performClick()
        rule.waitForIdle()
        assertEquals(DocumentCrop(), crop)
        assertEquals(listOf("apply", "apply", "apply", "apply", "autoLevel", "apply"), calls)
    }

    @Test
    fun `a straighten slider drag is one gesture`() {
        compose()
        rule.onNodeWithTag("photo.slider.crop.straighten").performTouchInput {
            down(center); moveBy(Offset(60f, 0f)); moveBy(Offset(60f, 0f)); up()
        }
        rule.waitForIdle()
        assertEquals("start", calls.first())
        assertEquals("end", calls.last())
        assertTrue(crop.straighten > -3f)
    }
}
