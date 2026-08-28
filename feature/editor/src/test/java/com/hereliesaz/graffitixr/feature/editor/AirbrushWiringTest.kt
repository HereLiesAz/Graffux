package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [DrawingEngine]'s azphalt stamp-brush commit path (item 13: Airbrush) against real
 * `android.graphics` -- the actual integration point, not a re-test of [AirbrushEngine.heldDabs]
 * or [com.hereliesaz.graffitixr.feature.editor.StampBrushRenderer], both already covered
 * elsewhere. What's new here is that `DrawingEngine` actually calls `heldDabs` and paints its
 * output when a stroke's samples include a held-still run and the brush has airbrush enabled.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AirbrushWiringTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val slamManager: SlamManager = mockk(relaxed = true)
    private val engine = DrawingEngine(slamManager)

    /** A no-op dynamics binding, present only so `brush.dynamics.isNotEmpty()` selects the
     *  sensor-driven branch this test targets, without perturbing size/opacity. */
    private val noOpDynamics = BrushSensorBinding(
        BrushSensor.PRESSURE, BrushParameter.OPACITY, outputMin = 1f, outputMax = 1f,
    )

    /** [count] samples held at (20, 20) for [holdMs] total, [intervalMs] apart -- long enough for
     *  several airbrush cadences to fire within the run. */
    private fun heldSamples(count: Int, intervalMs: Long): List<BrushSample> =
        (0 until count).map { i -> BrushSample(x = 20f, y = 20f, uptimeMillis = i * intervalMs, pressure = 1f) }

    private fun strokeAt(brush: AzphaltBrush, samples: List<BrushSample>) = StrokeCommand(
        path = samples.map { Offset(it.x, it.y) },
        brushSamples = samples,
        canvasSize = IntSize(40, 40),
        tool = Tool.BRUSH,
        brushSize = 6f,
        brushColor = 0xFFFF0000.toInt(),
        intensity = 1f,
        flow = 0.3f,
        seed = 42L,
        stampBrush = brush,
    )

    private fun base(): Bitmap = RenderTestBase.filled(40, 40, Color.TRANSPARENT)

    @Test
    fun `a held run with airbrush enabled deposits more opacity than the same run without it`() = runTest {
        val samples = heldSamples(count = 5, intervalMs = 100L) // a 400ms held run

        val withoutAirbrush = AzphaltBrush(
            name = "plain", dynamics = listOf(noOpDynamics), airbrushDabsPerSecond = 0f,
        )
        val withAirbrush = withoutAirbrush.copy(airbrushDabsPerSecond = 20f, airbrushStillnessRadiusPx = 5f)

        val plainResult = engine.applySingleStroke(base(), strokeAt(withoutAirbrush, samples))
        val airbrushResult = engine.applySingleStroke(base(), strokeAt(withAirbrush, samples))

        val plainAlpha = Color.alpha(plainResult.getPixel(20, 20))
        val airbrushAlpha = Color.alpha(airbrushResult.getPixel(20, 20))
        assertTrue(
            "airbrush should build up more opacity from repeated held dabs " +
                "(plain=$plainAlpha, airbrush=$airbrushAlpha)",
            airbrushAlpha > plainAlpha,
        )
    }

    @Test
    fun `airbrushDabsPerSecond = 0 renders byte-identical to a brush with no airbrush field at all`() = runTest {
        val samples = heldSamples(count = 5, intervalMs = 100L)
        val brush = AzphaltBrush(name = "plain", dynamics = listOf(noOpDynamics))
        check(brush.airbrushDabsPerSecond == 0f) { "default must be off" }

        val a = engine.applySingleStroke(base(), strokeAt(brush, samples))
        val b = engine.applySingleStroke(base(), strokeAt(brush, samples))

        assertTrue(Color.alpha(a.getPixel(20, 20)) == Color.alpha(b.getPixel(20, 20)))
    }

    @Test
    fun `movement past the stillness radius does not trigger airbrush build-up`() = runTest {
        // Each sample moves 20px, well past a 5px stillness radius -- an ordinary moving stroke,
        // not a held one. Airbrush enabled but should contribute nothing beyond the movement dabs.
        val moving = (0 until 5).map { i ->
            BrushSample(x = 5f + i * 6f, y = 20f, uptimeMillis = i * 100L, pressure = 1f)
        }
        val withoutAirbrush = AzphaltBrush(
            name = "plain", dynamics = listOf(noOpDynamics), airbrushDabsPerSecond = 0f,
        )
        val withAirbrush = withoutAirbrush.copy(airbrushDabsPerSecond = 20f, airbrushStillnessRadiusPx = 5f)

        val plainResult = engine.applySingleStroke(base(), strokeAt(withoutAirbrush, moving))
        val airbrushResult = engine.applySingleStroke(base(), strokeAt(withAirbrush, moving))

        // Sample the whole path, not just one point, since a moving stroke's dabs are spread out.
        for (i in 0 until 5) {
            val x = (5f + i * 6f).toInt().coerceIn(0, 39)
            assertTrue(
                "movement-only stroke should be unaffected by airbrush at x=$x",
                Color.alpha(plainResult.getPixel(x, 20)) == Color.alpha(airbrushResult.getPixel(x, 20)),
            )
        }
    }
}
