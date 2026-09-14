package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushContactConfig
import com.hereliesaz.graffitixr.common.azphalt.BrushSample
import com.hereliesaz.graffitixr.common.azphalt.BrushSignalSource
import com.hereliesaz.graffitixr.common.azphalt.BrushTelemetryMetadata
import com.hereliesaz.graffitixr.common.azphalt.BrushTelemetryProfile
import com.hereliesaz.graffitixr.common.azphalt.BrushTuftConfig
import com.hereliesaz.graffitixr.common.azphalt.PaintMedium
import com.hereliesaz.graffitixr.common.azphalt.SubstrateField
import com.hereliesaz.graffitixr.common.azphalt.SubstrateProfile
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

/** Commit/replay integration for the Phase 3 substrate context. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawingEngineSubstrateTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    private val width = 40
    private val height = 40
    private val engine = DrawingEngine(mockk<SlamManager>(relaxed = true))

    private val telemetry = BrushTelemetryMetadata(
        profile = BrushTelemetryProfile.STYLUS_HIGH_QUALITY,
        pressureConfidence = 0.98f,
        pressureSource = BrushSignalSource.STYLUS_SENSOR,
    )

    private val brush = AzphaltBrush(
        name = "substrate-commit",
        spacing = 0.2f,
        hardness = 1f,
        contact = BrushContactConfig(
            enabled = true,
            drag = 0f,
            pressureCoupling = 0.65f,
            pressureSplay = 0f,
            tufts = BrushTuftConfig(enabled = false),
        ),
    )

    private val samples = listOf(
        BrushSample(
            x = 14f,
            y = 20f,
            uptimeMillis = 0L,
            pressure = 0.2f,
            distancePx = 0f,
            speedPxPerMs = 0.5f,
            drawingAngleDeg = 0f,
            telemetry = telemetry,
        ),
        BrushSample(
            x = 26f,
            y = 20f,
            uptimeMillis = 20L,
            pressure = 0.2f,
            distancePx = 12f,
            speedPxPerMs = 0.5f,
            drawingAngleDeg = 0f,
            telemetry = telemetry,
        ),
    )

    private val stroke = StrokeCommand(
        path = samples.map { Offset(it.x, it.y) },
        brushSamples = samples,
        canvasSize = IntSize(width, height),
        tool = Tool.BRUSH,
        stampBrush = brush,
        brushSize = 10f,
        brushColor = Color.RED,
        intensity = 1f,
        flow = 1f,
        seed = 17L,
    )

    private val roughField = SubstrateField(1, 1, byteArrayOf(0xFF.toByte()))
    private val roughProfile = SubstrateProfile(heightScale = 1f)
    private val substrateMedium = PaintMedium(substrateResponse = 1f)

    private fun base(): Bitmap = RenderTestBase.filled(width, height, Color.TRANSPARENT)

    private fun pixels(bitmap: Bitmap): List<Int> = IntArray(width * height).also {
        bitmap.getPixels(it, 0, width, 0, 0, width, height)
    }.toList()

    private fun anyPaint(bitmap: Bitmap): Boolean = pixels(bitmap).any { Color.alpha(it) > 0 }

    @Test
    fun `applySingleStroke forwards substrate context into stamp rendering`() = runTest {
        val legacy = engine.applySingleStroke(base(), stroke)
        val substrate = SubstrateRenderContext(roughProfile, roughField, substrateMedium)
        val gated = engine.applySingleStroke(base(), stroke, substrate = substrate)

        assertTrue("legacy null-context path should still paint", anyPaint(legacy))
        assertTrue("full-height tooth should block this light contact", !anyPaint(gated))
    }

    @Test
    fun `composite replay matches single-stroke commit with the same substrate context`() = runTest {
        val substrate = SubstrateRenderContext(roughProfile, roughField, substrateMedium)

        val committed = engine.applySingleStroke(base(), stroke, substrate = substrate)
        val replayed = engine.composite(base(), listOf(stroke), substrate = substrate)

        assertEquals(pixels(committed), pixels(replayed))
    }

    @Test
    fun `commit path reuses existing paint height to fill substrate valleys`() = runTest {
        val paintHeight = FloatArray(width * height) { 1f }
        val substrate = SubstrateRenderContext(
            roughProfile,
            roughField,
            substrateMedium,
            paintHeight = paintHeight,
        )

        val painted = engine.applySingleStroke(
            base(),
            stroke,
            heightMap = paintHeight,
            substrate = substrate,
        )

        assertTrue("filled local paint height should remove the tooth barrier", anyPaint(painted))
    }
}
