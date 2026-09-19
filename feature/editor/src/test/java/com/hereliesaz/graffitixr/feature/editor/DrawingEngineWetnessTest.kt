package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.feature.editor.util.ColorSmudgeEngine
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawingEngineWetnessTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val width = 48
    private val height = 24
    private val engine = DrawingEngine(mockk<SlamManager>(relaxed = true))

    private fun base(): Bitmap = RenderTestBase.filled(width, height, Color.WHITE).also { bitmap ->
        val pixels = IntArray(width * height) { index ->
            if (index % width < width / 2) Color.RED else Color.BLUE
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun stroke(startMs: Long, y: Float, seed: Long): StrokeCommand {
        val points = listOf(Offset(8f, y), Offset(38f, y))
        val samples = listOf(
            BrushSample(x = 8f, y = y, uptimeMillis = startMs, pressure = 1f),
            BrushSample(x = 38f, y = y, uptimeMillis = startMs + 120L, pressure = 1f),
        )
        return StrokeCommand(
            path = points,
            brushSamples = samples,
            colorSmudgeSettings = ColorSmudgeEngine.Settings(
                smudgeRate = 0.8f,
                colorRate = 0.45f,
                dilution = 0.65f,
                pickupRate = 0.35f,
            ),
            canvasSize = IntSize(width, height),
            tool = Tool.SMUDGE,
            brushSize = 8f,
            brushColor = Color.YELLOW,
            intensity = 0.5f,
            seed = seed,
        )
    }

    @Test
    fun `sequential commit and full replay produce identical pixels and wetness`() = runTest {
        val first = stroke(1_000L, 10f, 71L)
        val second = stroke(1_500L, 12f, 72L)

        val commitWetness = WetnessReplayState.empty(width, height)
        val afterFirst = engine.applySingleStroke(base(), first, wetnessState = commitWetness)
        val committed = engine.applySingleStroke(afterFirst, second, wetnessState = commitWetness)

        val replayWetness = WetnessReplayState.empty(width, height)
        val replayed = engine.composite(base(), listOf(first, second), wetnessState = replayWetness)

        val committedPixels = IntArray(width * height)
        val replayedPixels = IntArray(width * height)
        committed.getPixels(committedPixels, 0, width, 0, 0, width, height)
        replayed.getPixels(replayedPixels, 0, width, 0, 0, width, height)

        assertArrayEquals(committedPixels, replayedPixels)
        assertArrayEquals(commitWetness.snapshot(), replayWetness.snapshot(), 0f)
        assertTrue(commitWetness.field.activeTileCount > 0)
    }
}
