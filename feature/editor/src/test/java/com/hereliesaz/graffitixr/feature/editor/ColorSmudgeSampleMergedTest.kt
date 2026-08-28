package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Engine-level coverage for the "Sample Merged" primitive (roadmap item 11): Smear/Dulling colour
 * pickup can read from a separately supplied composite instead of the active layer's own pixels,
 * while painting always still writes to the active layer. No caller wires a real multi-layer
 * composite into [ColorSmudgeEngine.apply] yet — see the roadmap doc for the follow-up scope.
 *
 * Robolectric (not a plain JVM test) because Dulling's weighted-average pickup calls
 * `android.graphics.Color.argb`, which the mockable android.jar stubs to return 0 rather than
 * throw — a plain unit test would silently see every Dulling pickup as transparent black.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ColorSmudgeSampleMergedTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private fun flat(width: Int, height: Int, color: Int): IntArray = IntArray(width * height) { color }

    private val stroke = List(24) { Offset((8 + it).toFloat(), 16f) }
    private val w = 48
    private val h = 32

    @Test
    fun `smear with no sampleSource behaves exactly like sampling the active layer`() {
        val settingsOff = ColorSmudgeEngine.Settings(radiusPx = 6f, smudgeRate = 1f, sampleMerged = false)
        val settingsOnNoSource = settingsOff.copy(sampleMerged = true)
        val a = flat(w, h, Color.WHITE)
        val b = flat(w, h, Color.WHITE)
        ColorSmudgeEngine.apply(a, w, h, stroke, settingsOff)
        ColorSmudgeEngine.apply(b, w, h, stroke, settingsOnNoSource) // sampleMerged=true but no source
        assertArrayEquals(a, b)
    }

    @Test
    fun `smear pickup reads the merged composite, not the active layer, when enabled`() {
        // Active layer (pixels) is flat white throughout; the merged composite has a blue block
        // under the stroke. With Sample Merged on, the carried colour should reflect the composite.
        val pixels = flat(w, h, Color.WHITE)
        val composite = IntArray(w * h) { i -> if (i % w < w / 2) Color.BLUE else Color.WHITE }
        val settings = ColorSmudgeEngine.Settings(radiusPx = 6f, smudgeRate = 1f, sampleMerged = true)
        ColorSmudgeEngine.apply(pixels, w, h, stroke, settings, sampleSource = composite)

        val painted = pixels[16 * w + 30]
        assertTrue(
            "expected blue pickup from the composite, got argb=${Integer.toHexString(painted)}",
            Color.blue(painted) > Color.red(painted),
        )
    }

    @Test
    fun `smear pickup ignores the composite when sampleMerged is off even if a source is supplied`() {
        val pixels = flat(w, h, Color.WHITE)
        val composite = IntArray(w * h) { i -> if (i % w < w / 2) Color.BLUE else Color.WHITE }
        val settings = ColorSmudgeEngine.Settings(radiusPx = 6f, smudgeRate = 1f, sampleMerged = false)
        ColorSmudgeEngine.apply(pixels, w, h, stroke, settings, sampleSource = composite)

        val painted = pixels[16 * w + 30]
        // Active layer was flat white the whole way, so nothing blue could have been picked up.
        assertEquals(0, Color.blue(painted) - Color.green(painted))
    }

    @Test
    fun `dulling pickup reads the merged composite when enabled`() {
        val pixels = flat(w, h, Color.WHITE)
        val composite = IntArray(w * h) { i -> if (i % w < w / 2) Color.BLUE else Color.WHITE }
        val settings = ColorSmudgeEngine.Settings(
            mode = ColorSmudgeEngine.Mode.DULLING, radiusPx = 6f, smudgeRate = 1f, sampleMerged = true,
        )
        ColorSmudgeEngine.apply(pixels, w, h, stroke, settings, sampleSource = composite)

        // x=12 keeps the whole radius-6 pickup window inside the composite's pure-blue half
        // (blue for x < w/2 = 24), away from the boundary that would dilute the average.
        val painted = pixels[16 * w + 12]
        assertTrue(
            "expected blue pickup from the composite, got argb=${Integer.toHexString(painted)}",
            Color.blue(painted) > Color.red(painted),
        )
    }

    @Test
    fun `painting always writes to the active layer, never the supplied composite`() {
        val pixels = flat(w, h, Color.WHITE)
        val composite = IntArray(w * h) { i -> if (i % w < w / 2) Color.BLUE else Color.WHITE }
        val compositeBefore = composite.copyOf()
        val settings = ColorSmudgeEngine.Settings(radiusPx = 6f, smudgeRate = 1f, sampleMerged = true)
        ColorSmudgeEngine.apply(pixels, w, h, stroke, settings, sampleSource = composite)

        assertArrayEquals("the supplied composite must never be mutated", compositeBefore, composite)
        assertTrue("the active layer should have actually been painted", pixels.any { it != Color.WHITE })
    }

    @Test
    fun `a mismatched-size sampleSource degrades safely to sampling the active layer`() {
        val settingsOff = ColorSmudgeEngine.Settings(radiusPx = 6f, smudgeRate = 1f, sampleMerged = true)
        val a = flat(w, h, Color.WHITE)
        val b = flat(w, h, Color.WHITE)
        val wrongSize = IntArray((w * h) / 2) { Color.BLUE }
        ColorSmudgeEngine.apply(a, w, h, stroke, settingsOff.copy(sampleMerged = false))
        ColorSmudgeEngine.apply(b, w, h, stroke, settingsOff, sampleSource = wrongSize)
        assertArrayEquals(a, b)
    }
}
