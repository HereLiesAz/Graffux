package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushSampleBuilder
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression coverage for the Krita-style sensor pipeline's most important invariant: the telemetry
 * that shapes a live stamp stroke is stored on the command and produces the same pixels when replayed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DynamicStampReplayTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val engine = DrawingEngine(mockk<SlamManager>(relaxed = true))
    private val canvasSize = IntSize(64, 40)

    private fun base(): Bitmap = RenderTestBase.filled(canvasSize.width, canvasSize.height, Color.WHITE)

    private val pressureBrush = AzphaltBrush(
        name = "Pressure stamp",
        spacing = 0.2f,
        hardness = 1f,
        dynamics = listOf(
            BrushSensorBinding(
                sensor = BrushSensor.PRESSURE,
                parameter = BrushParameter.SIZE,
                outputMin = 0.2f,
                outputMax = 1f,
            )
        ),
    )

    private fun command(brush: AzphaltBrush = pressureBrush): StrokeCommand {
        val builder = BrushSampleBuilder()
        val samples = listOf(
            builder.add(8f, 20f, 0L, pressure = 0.15f),
            builder.add(20f, 20f, 12L, pressure = 0.35f),
            builder.add(34f, 20f, 24L, pressure = 0.65f),
            builder.add(54f, 20f, 36L, pressure = 1f),
        )
        return StrokeCommand(
            path = samples.map { Offset(it.x, it.y) },
            pressures = samples.map { it.pressure },
            brushSamples = samples,
            canvasSize = canvasSize,
            tool = Tool.BRUSH,
            brushSize = 16f,
            brushColor = Color.RED,
            intensity = 0.5f,
            stampBrush = brush,
            flow = 1f,
            seed = 0xBADC0FFEE0DDF00DuL.toLong(),
        )
    }

    @Test
    fun `dynamic stamp commit is pixel-identical to replay`() = runTest {
        val cmd = command()
        val committed = engine.applySingleStroke(base(), cmd)
        val replayed = engine.composite(base(), listOf(cmd))

        var differing = 0
        for (y in 0 until canvasSize.height) for (x in 0 until canvasSize.width) {
            if (committed.getPixel(x, y) != replayed.getPixel(x, y)) differing++
        }
        assertEquals("dynamic stamp changed between commit and replay", 0, differing)
    }

    @Test
    fun `recorded pressure telemetry changes rendered stamp geometry`() = runTest {
        val dynamic = engine.applySingleStroke(base(), command())
        val static = engine.applySingleStroke(
            base(),
            command(pressureBrush.copy(dynamics = emptyList())),
        )

        var changedPixels = 0
        var dynamicPainted = 0
        var staticPainted = 0
        for (y in 0 until canvasSize.height) for (x in 0 until canvasSize.width) {
            val d = dynamic.getPixel(x, y)
            val s = static.getPixel(x, y)
            if (d != s) changedPixels++
            if (d != Color.WHITE) dynamicPainted++
            if (s != Color.WHITE) staticPainted++
        }

        assertTrue("pressure route should visibly change the stroke", changedPixels > 0)
        assertTrue("low-pressure start should cover less area than a full-size static brush", dynamicPainted < staticPainted)
        assertNotEquals(Color.WHITE, dynamic.getPixel(54, 20))
    }
}
