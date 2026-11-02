package com.hereliesaz.graffux

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
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
 * The rotation slider docked at the bottom of the screen (spec/3d-space.md, "Rotation slider"):
 * there while the sheet is closed or hidden for drawing, gone while it is open, live during a stroke,
 * and its touches never reach the canvas.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi", application = Application::class)
class RotationSliderDockUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var sheetOpen by mutableStateOf(false)
    private var drawing by mutableStateOf(false)
    private var canvasTouches = 0
    private var sliderStarts = 0

    private fun compose() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    // The canvas: counts every gesture that reaches it.
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectDragGestures(onDragStart = { canvasTouches++ }) { _, _ -> }
                        },
                    )
                    RotationSliderDock(sheetOpen = sheetOpen, sheetHidden = { drawing }, sideClearance = 80.dp) {
                        RotationSlider(-90f, { sliderStarts++ }, {}, {}, {}, "Rotate")
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun shown() = rule.onAllNodesWithTag("rotationSlider").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `hidden while the sheet is open`() {
        sheetOpen = true
        compose()
        assertTrue(!shown())
    }

    @Test
    fun `shown at the bottom while the sheet is closed, above the insets and between the rails`() {
        compose()
        assertTrue(shown())
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val slider = rule.onNodeWithTag("rotationSlider").fetchSemanticsNode().boundsInRoot
        val d = rule.density.density
        assertEquals(root.bottom - CarouselBottomInset.value * d, slider.bottom, 1f)
        assertTrue(slider.left >= 80f * d - 1f && slider.right <= root.right - 80f * d + 1f)
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File("build/sheet-shots").mkdirs()
        File("build/sheet-shots/closed.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `still shown and not ghosted during a stroke, even with the sheet open`() {
        sheetOpen = true
        compose()
        drawing = true // the sheet hides for the stroke
        rule.waitForIdle()
        assertTrue(shown())
        // Drawn at full strength: the slider's thumb and track show against the black canvas.
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val b = rule.onNodeWithTag("rotationSlider").fetchSemanticsNode().boundsInRoot
        var marked = 0
        for (y in b.top.toInt() until b.bottom.toInt()) {
            for (x in b.left.toInt() until b.right.toInt()) {
                val p = bmp.getPixel(x, y)
                if ((p shr 16 and 0xFF) > 128 && (p shr 8 and 0xFF) > 128) marked++
            }
        }
        assertTrue("slider pixels drawn: $marked", marked > 50)
        drawing = false
        rule.waitForIdle()
        assertTrue("the open sheet takes it away again", !shown())
    }

    @Test
    fun `its touches never paint`() {
        compose()
        rule.onNodeWithTag("rotationSlider").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(1, sliderStarts)
        assertEquals(0, canvasTouches)
    }
}
