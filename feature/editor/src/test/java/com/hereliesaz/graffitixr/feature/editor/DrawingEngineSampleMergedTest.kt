package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [DrawingEngine]'s Tool.SMUDGE branch actually calling [ExportManager.compositeOtherLayersForSampling]
 * and threading the result into [ColorSmudgeEngine.apply]'s `sampleSource` -- the real end-to-end
 * wiring for item 11 ("Sample Merged"), as opposed to [ColorSmudgeSampleMergedTest] (the primitive
 * itself, fed a hand-built composite) or [com.hereliesaz.graffitixr.feature.editor.export.ExportManagerSampleMergedTest]
 * (the coordinate remap alone). No physical device involved: the active layer starts fully
 * transparent, so any picked-up colour can only have come from the other layer's composite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawingEngineSampleMergedTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val slamManager: SlamManager = mockk(relaxed = true)
    private val engine = DrawingEngine(slamManager)

    private val w = 48
    private val h = 32

    private fun stroke(sampleMerged: Boolean) = StrokeCommand(
        path = List(24) { Offset((8 + it).toFloat(), 16f) },
        canvasSize = IntSize(w, h),
        tool = Tool.SMUDGE,
        brushSize = 12f,
        brushColor = Color.BLACK,
        intensity = 1f,
        colorSmudgeSettings = ColorSmudgeEngine.Settings(
            mode = ColorSmudgeEngine.Mode.SMEAR,
            smudgeRate = 1f,
            colorRate = 0f,
            opacity = 1f,
            radiusPx = 6f,
            sampleMerged = sampleMerged,
        ),
        seed = 7L,
    )

    @Test
    fun `sample merged picks up colour from another visible layer, not the empty active layer`() = runTest {
        val active = RenderTestBase.filled(w, h, Color.TRANSPARENT)
        val other = Layer(
            id = "other", name = "other",
            bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until w) for (y in 0 until h) {
                    setPixel(x, y, if (x < w / 2) Color.BLUE else Color.WHITE)
                }
            },
        )

        val result = engine.applySingleStroke(active, stroke(sampleMerged = true), otherLayers = listOf(other))

        val painted = result.getPixel(30, 16)
        assertTrue(
            "expected a blue-leaning pickup from the other layer, got argb=${Integer.toHexString(painted)}",
            Color.blue(painted) >= Color.red(painted),
        )
        assertTrue("expected the stroke to have deposited some opacity", Color.alpha(painted) > 0)
    }

    @Test
    fun `sample merged off leaves an empty active layer untouched, even with another layer present`() = runTest {
        val active = RenderTestBase.filled(w, h, Color.TRANSPARENT)
        val other = Layer(
            id = "other", name = "other",
            bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until w) for (y in 0 until h) setPixel(x, y, Color.BLUE)
            },
        )

        val result = engine.applySingleStroke(active, stroke(sampleMerged = false), otherLayers = listOf(other))

        // Smear on an empty (transparent) active layer with nothing to pick up from itself and
        // Sample Merged off should deposit nothing -- there is no colour anywhere to smear.
        assertEquals(0, Color.alpha(result.getPixel(30, 16)))
    }

    @Test
    fun `sample merged with no other layers behaves exactly like it being off`() = runTest {
        val active = RenderTestBase.filled(w, h, Color.TRANSPARENT)

        val on = engine.applySingleStroke(active, stroke(sampleMerged = true), otherLayers = emptyList())
        val off = engine.applySingleStroke(active, stroke(sampleMerged = false), otherLayers = emptyList())

        assertEquals(Color.alpha(off.getPixel(30, 16)), Color.alpha(on.getPixel(30, 16)))
    }
}
