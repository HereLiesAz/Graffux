package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The built-in round brush's stroke-level [Tool.BRUSH] opacity: a whole-stroke ceiling, not a
 * per-dab build-up. The regression this guards against is a translucent stroke that loops back on
 * itself painting darker where it self-overlaps — the natural (and correct) result of drawing each
 * segment straight onto the layer with SRC_OVER, but not what "Opacity" is supposed to mean.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BrushOpacityRenderTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    /** A tight loop: travels right, then doubles back over the same row — guaranteed self-overlap. */
    private fun loopingStroke(size: Int): List<Offset> {
        val y = (size / 2).toFloat()
        val out = ArrayList<Offset>()
        for (x in 4 until size - 4) out.add(Offset(x.toFloat(), y))
        for (x in (size - 5) downTo 4) out.add(Offset(x.toFloat(), y))
        return out
    }

    @Test
    fun `a translucent self-overlapping stroke does not paint darker where it crosses itself`() = runBlocking {
        val size = 64
        val blank = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888) // fully transparent
        val stroke = loopingStroke(size)

        val painted = ImageProcessor.applyToolToBitmap(
            blank, stroke, Tool.BRUSH,
            brushSize = 10f, brushColor = Color.BLACK, opacity = 0.5f,
        )

        val mid = size / 2
        val alpha = RenderTestBase.alphaAt(painted, mid, mid)
        // Every point on this stroke was painted twice (out and back) — if the old per-segment
        // direct-draw behaviour were still in effect, that pixel would be ~0.75 alpha
        // (1 - (1-0.5)^2), not 0.5. It must land at exactly the requested ceiling.
        assertTrue("expected alpha near 127 (0.5 opacity), got $alpha", kotlin.math.abs(alpha - 127) <= 2)
        assertTrue("expected translucent, not opaque", alpha < 200)
    }

    @Test
    fun `opacity 1 is unaffected by the compositing change`() = runBlocking {
        val size = 64
        val blank = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val stroke = loopingStroke(size)

        val painted = ImageProcessor.applyToolToBitmap(
            blank, stroke, Tool.BRUSH,
            brushSize = 10f, brushColor = Color.BLACK, opacity = 1f,
        )

        val mid = size / 2
        assertEquals(255, RenderTestBase.alphaAt(painted, mid, mid))
    }
}
