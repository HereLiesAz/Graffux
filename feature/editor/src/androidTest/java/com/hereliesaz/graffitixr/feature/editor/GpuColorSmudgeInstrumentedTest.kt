package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.nativebridge.GpuStampEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a Smudge stroke must do to the pixels, checked on the GPU path every committed stroke takes
 * ([GpuColorSmudge]). These properties used to be pinned against a CPU reference that no stroke
 * ran; there is no CPU smudge any more, so they are asserted on the real renderer. A device whose
 * wgpu engine cannot initialise fails here instead of skipping: Smudge cannot paint there.
 */
@RunWith(AndroidJUnit4::class)
class GpuColorSmudgeInstrumentedTest {

    private val width = 96
    private val height = 32

    /** Red on the left quarter, white elsewhere. */
    private fun redBlock(): Bitmap = Bitmap.createBitmap(
        IntArray(width * height) { i -> if (i % width < width / 4) Color.RED else Color.WHITE },
        width, height, Bitmap.Config.ARGB_8888,
    ).copy(Bitmap.Config.ARGB_8888, true)

    private fun smudge(source: Bitmap, stroke: List<Offset>, settings: ColorSmudgeEngine.Settings): Bitmap {
        val engine = GpuStampEngine()
        try {
            assertTrue("wgpu engine failed to initialise", engine.init(width, height))
            assertTrue(engine.upload(source))
            val plans = ColorSmudgeEngine.resolvePlans(stroke, width, height, settings, strokeSeed = 7L)
            assertTrue(GpuColorSmudge.stamp(engine, plans, settings, null, width, height))
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            assertTrue(engine.readback(out))
            return out
        } finally {
            engine.destroy()
        }
    }

    private fun redness(argb: Int) = Color.red(argb) - Color.green(argb)

    private val settings = ColorSmudgeEngine.Settings(radiusPx = 6f, smudgeRate = 0.9f)

    @Test
    fun smearDragsColourAlongTheStroke() {
        val stroke = List(40) { Offset((16 + it).toFloat(), 16f) }
        val out = smudge(redBlock(), stroke, settings)
        assertTrue("red should be carried right of the block", redness(out.getPixel(width / 4 + 8, 16)) > 20)
    }

    @Test
    fun smearDoesNotCarryColourBackwards() {
        val stroke = List(40) { Offset((70 - it).toFloat(), 16f) }
        val out = smudge(redBlock(), stroke, settings)
        assertTrue("a leftward drag must not carry red rightwards", redness(out.getPixel(width / 4 + 8, 16)) <= 20)
    }

    @Test
    fun onlyPixelsUnderTheStrokeChange() {
        val source = redBlock()
        val stroke = List(40) { Offset((16 + it).toFloat(), 6f) }
        val out = smudge(source, stroke, settings)
        for (x in 0 until width) {
            assertEquals("row 28 is far below the stroke", source.getPixel(x, 28), out.getPixel(x, 28))
        }
    }

    @Test
    fun flatColourIsLeftAlone() {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val out = smudge(source, List(40) { Offset((16 + it).toFloat(), 16f) }, settings)
        for (x in 0 until width step 4) {
            for (y in 0 until height step 4) {
                val p = out.getPixel(x, y)
                assertTrue("($x,$y) changed to ${Integer.toHexString(p)}", kotlin.math.abs(Color.blue(p) - 255) <= 2)
            }
        }
    }
}
