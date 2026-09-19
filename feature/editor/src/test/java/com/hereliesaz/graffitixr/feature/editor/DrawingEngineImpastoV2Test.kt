package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.ImpastoV2Workspace
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
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
class DrawingEngineImpastoV2Test {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val width = 48
    private val height = 32
    private val engine = DrawingEngine(mockk<SlamManager>(relaxed = true))

    private val brush = AzphaltBrush(
        name = "impasto-v2",
        spacing = 0.2f,
        hardness = 1f,
        impastoThicknessRate = 0.45f,
        impastoWetness = 0.7f,
        impastoPickupRate = 0.2f,
        impastoLevelingRate = 0.55f,
        impastoBody = 0.3f,
        impastoWetGloss = 0.8f,
    )

    private fun base(): Bitmap = RenderTestBase.filled(width, height, Color.TRANSPARENT)

    private fun stroke(y: Float, startMs: Long, seed: Long): StrokeCommand {
        val points = listOf(Offset(8f, y), Offset(40f, y))
        val samples = listOf(
            BrushSample(8f, y, startMs, pressure = 1f),
            BrushSample(40f, y, startMs + 100L, pressure = 1f),
        )
        return StrokeCommand(
            path = points,
            brushSamples = samples,
            canvasSize = IntSize(width, height),
            tool = Tool.BRUSH,
            stampBrush = brush,
            brushSize = 10f,
            brushColor = Color.RED,
            intensity = 1f,
            flow = 1f,
            seed = seed,
        )
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(width * height).also {
        bitmap.getPixels(it, 0, width, 0, 0, width, height)
    }

    @Test
    fun sequentialCommitAndFullReplayMatchAcrossAllMaterialChannels() = runTest {
        val strokes = listOf(
            stroke(14f, 1_000L, 101L),
            stroke(16f, 1_600L, 102L),
        )

        val commitHeight = FloatArray(width * height)
        val commitStructure = FloatArray(width * height) { 1f }
        val commitWetness = WetnessReplayState.empty(width, height)
        val commitWorkspace = ImpastoV2Workspace(width, height)

        var committed = base()
        for (command in strokes) {
            committed = engine.applySingleStroke(
                base = committed,
                command = command,
                heightMap = commitHeight,
                wetnessState = commitWetness,
                structureMap = commitStructure,
                impastoWorkspace = commitWorkspace,
            )
        }

        val replayHeight = FloatArray(width * height)
        val replayStructure = FloatArray(width * height) { 1f }
        val replayWetness = WetnessReplayState.empty(width, height)
        val replayed = engine.composite(
            base = base(),
            strokes = strokes,
            heightMap = replayHeight,
            wetnessState = replayWetness,
            structureMap = replayStructure,
            impastoWorkspace = ImpastoV2Workspace(width, height),
        )

        assertArrayEquals(pixels(committed), pixels(replayed))
        assertArrayEquals(commitHeight, replayHeight, 0f)
        assertArrayEquals(commitStructure, replayStructure, 0f)
        assertArrayEquals(commitWetness.snapshot(), replayWetness.snapshot(), 0f)
        assertTrue(commitHeight.any { it > 0f })
        assertTrue(commitStructure.any { it < 1f })
        assertTrue(commitWetness.snapshot().any { it > 0f })
    }

    @Test
    fun defaultImpastoBrushRemainsV1CompatibilityMode() {
        val legacy = AzphaltBrush(name = "legacy", impastoThicknessRate = 0.5f)
        assertFalse(legacy.usesImpastoV2())
    }
}
