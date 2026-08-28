package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.common.model.Tool
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
 * [DrawingEngine]'s stamp-brush commit path actually depositing into and shading against a
 * caller-supplied height map (roadmap item 12: Impasto) -- the real integration point, not a
 * re-test of [com.hereliesaz.graffitixr.common.azphalt.ImpastoEngine] itself (covered elsewhere).
 * Commit/replay-only, same scoping as Airbrush (item 13): not reachable from the live incremental
 * preview.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawingEngineImpastoTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val slamManager: SlamManager = mockk(relaxed = true)
    private val engine = DrawingEngine(slamManager)

    private val w = 40
    private val h = 40

    /** A no-op dynamics binding, present only so the sensor-aware `dynamicDabs` path is taken --
     *  mirrors [AirbrushWiringTest]'s identical need. */
    private val noOpDynamics = BrushSensorBinding(
        BrushSensor.PRESSURE, BrushParameter.OPACITY, outputMin = 1f, outputMax = 1f,
    )

    private fun straightStroke(brush: AzphaltBrush) = StrokeCommand(
        path = List(12) { Offset(10f + it * 1.5f, 20f) },
        canvasSize = IntSize(w, h),
        tool = Tool.BRUSH,
        stampBrush = brush,
        brushSize = 10f,
        brushColor = Color.RED,
        intensity = 1f,
        flow = 1f,
        seed = 5L,
    )

    private fun base(): android.graphics.Bitmap = RenderTestBase.filled(w, h, Color.TRANSPARENT)

    @Test
    fun `a positive thickness rate deposits into the supplied height map`() = runTest {
        val brush = AzphaltBrush(name = "impasto", spacing = 0.2f, hardness = 1f, impastoThicknessRate = 0.5f)
        val heightMap = FloatArray(w * h)

        engine.applySingleStroke(base(), straightStroke(brush), heightMap = heightMap)

        assertTrue("expected some height to have been deposited under the stroke", heightMap.any { it > 0f })
    }

    @Test
    fun `impastoThicknessRate = 0 never touches the supplied height map`() = runTest {
        val brush = AzphaltBrush(name = "flat", spacing = 0.2f, impastoThicknessRate = 0f)
        val heightMap = FloatArray(w * h)

        engine.applySingleStroke(base(), straightStroke(brush), heightMap = heightMap)

        assertTrue(heightMap.all { it == 0f })
    }

    @Test
    fun `a null height map renders identically to impastoThicknessRate = 0`() = runTest {
        val brush = AzphaltBrush(name = "impasto", spacing = 0.2f, impastoThicknessRate = 0.5f)

        val withoutHeightMap = engine.applySingleStroke(base(), straightStroke(brush), heightMap = null)
        val flat = engine.applySingleStroke(
            base(), straightStroke(brush.copy(impastoThicknessRate = 0f)), heightMap = null,
        )

        val a = IntArray(w * h); withoutHeightMap.getPixels(a, 0, w, 0, 0, w, h)
        val b = IntArray(w * h); flat.getPixels(b, 0, w, 0, 0, w, h)
        assertEquals(
            "no shading should be applied when no height map is supplied, even with a positive rate",
            a.toList(), b.toList(),
        )
    }

    @Test
    fun `shading visibly perturbs colour where height was deposited`() = runTest {
        val brush = AzphaltBrush(name = "impasto", spacing = 0.2f, hardness = 1f, impastoThicknessRate = 0.9f)
        val heightMap = FloatArray(w * h)

        val shaded = engine.applySingleStroke(base(), straightStroke(brush), heightMap = heightMap)
        val plain = engine.applySingleStroke(base(), straightStroke(brush.copy(impastoThicknessRate = 0f)))

        val shadedPixels = IntArray(w * h); shaded.getPixels(shadedPixels, 0, w, 0, 0, w, h)
        val plainPixels = IntArray(w * h); plain.getPixels(plainPixels, 0, w, 0, 0, w, h)
        assertTrue(
            "expected Impasto shading to change at least some painted pixels relative to the flat stroke",
            shadedPixels.toList() != plainPixels.toList(),
        )
    }

    @Test
    fun `impasto also deposits height on the sensor-aware (dynamics) dab path`() = runTest {
        val brush = AzphaltBrush(
            name = "impasto-dynamic", spacing = 0.2f, hardness = 1f, impastoThicknessRate = 0.5f,
            dynamics = listOf(noOpDynamics),
        )
        val samples = List(6) { com.hereliesaz.graffitixr.common.azphalt.BrushSample(x = 10f + it * 3f, y = 20f, uptimeMillis = it * 20L, pressure = 1f) }
        val stroke = straightStroke(brush).copy(brushSamples = samples, path = samples.map { Offset(it.x, it.y) })
        val heightMap = FloatArray(w * h)

        engine.applySingleStroke(base(), stroke, heightMap = heightMap)

        assertTrue(heightMap.any { it > 0f })
    }
}
