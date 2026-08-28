package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.feature.editor.RenderTestBase
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ExportManager.compositeOtherLayersForSampling] — item 11's coordinate-remap primitive: the
 * actual missing piece that previously blocked wiring "Sample Merged" into a real caller (see the
 * roadmap doc's prior "what is deliberately not done" note). Verified here against known, hand-
 * computed transforms rather than a device, since the matrix math is exactly reproducible without
 * one -- [DrawingEngineSampleMergedTest] separately covers that the real paint pipeline actually
 * calls this and gets a materially different result.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExportManagerSampleMergedTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val manager = ExportManager()

    private fun layer(id: String, bitmap: Bitmap, offset: Offset = Offset.Zero, scale: Float = 1f) =
        Layer(id = id, name = id, bitmap = bitmap, offset = offset, scale = scale)

    @Test
    fun `identical transform and square aspect composites the other layer pixel-for-pixel`() {
        val anchorBitmap = RenderTestBase.filled(20, 20, Color.TRANSPARENT)
        val otherBitmap = RenderTestBase.filled(20, 20, Color.RED)

        val result = manager.compositeOtherLayersForSampling(
            anchorBitmap, anchorScale = 1f, anchorOffset = Offset.Zero, anchorRotationZ = 0f,
            otherLayers = listOf(layer("other", otherBitmap)),
            screenWidth = 20, screenHeight = 20,
        )

        assertEquals(20, result.width)
        assertEquals(20, result.height)
        for (x in 0 until 20) for (y in 0 until 20) {
            assertEquals("mismatch at ($x,$y)", Color.RED, result.getPixel(x, y))
        }
    }

    @Test
    fun `output is always exactly the anchor's own resolution regardless of maxDim capping elsewhere`() {
        // A large anchor (bigger than compositeToLayerSpace's default 2048 cap) must come back
        // uncapped here, since this feeds a sampleSource that must match the anchor's own pixel
        // array size exactly -- a capped/scaled result would silently misalign with pixels[].
        val anchorBitmap = RenderTestBase.filled(2200, 100, Color.TRANSPARENT)
        val otherBitmap = RenderTestBase.filled(2200, 100, Color.BLUE)

        val result = manager.compositeOtherLayersForSampling(
            anchorBitmap, anchorScale = 1f, anchorOffset = Offset.Zero, anchorRotationZ = 0f,
            otherLayers = listOf(layer("other", otherBitmap)),
            screenWidth = 2200, screenHeight = 100,
        )

        assertEquals(2200, result.width)
        assertEquals(100, result.height)
    }

    @Test
    fun `a layer offset on screen shifts where its content lands in the anchor's local space`() {
        // Other layer's content is a hard left(red)/right(blue) split; offset it +10px right on
        // screen relative to the (unshifted) anchor. In the anchor's own local space that content
        // should land shifted +10px right too -- so anchor-local columns [10,20) should show the
        // other layer's columns [0,10) (red), and the anchor's own columns [0,10) should be
        // untouched (nothing painted there).
        val anchorBitmap = RenderTestBase.filled(20, 20, Color.TRANSPARENT)
        val otherBitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 20) for (y in 0 until 20) {
                setPixel(x, y, if (x < 10) Color.RED else Color.BLUE)
            }
        }

        val result = manager.compositeOtherLayersForSampling(
            anchorBitmap, anchorScale = 1f, anchorOffset = Offset.Zero, anchorRotationZ = 0f,
            otherLayers = listOf(layer("other", otherBitmap, offset = Offset(10f, 0f))),
            screenWidth = 20, screenHeight = 20,
        )

        for (y in 0 until 20) {
            assertEquals("anchor-local (0,$y) should be untouched", Color.TRANSPARENT, result.getPixel(0, y))
            assertEquals("anchor-local (15,$y) should show the other layer's red half", Color.RED, result.getPixel(15, y))
        }
    }

    @Test
    fun `an invisible other layer is excluded from the composite`() {
        val anchorBitmap = RenderTestBase.filled(10, 10, Color.TRANSPARENT)
        val otherBitmap = RenderTestBase.filled(10, 10, Color.RED)

        val result = manager.compositeOtherLayersForSampling(
            anchorBitmap, anchorScale = 1f, anchorOffset = Offset.Zero, anchorRotationZ = 0f,
            otherLayers = listOf(layer("other", otherBitmap).copy(isVisible = false)),
            screenWidth = 10, screenHeight = 10,
        )

        assertEquals(Color.TRANSPARENT, result.getPixel(5, 5))
    }
}
