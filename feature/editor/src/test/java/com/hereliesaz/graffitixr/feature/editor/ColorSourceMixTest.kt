package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.BrushColorSource
import com.hereliesaz.graffitixr.common.azphalt.BrushParameter
import com.hereliesaz.graffitixr.common.azphalt.BrushSampleBuilder
import com.hereliesaz.graffitixr.common.azphalt.BrushSensor
import com.hereliesaz.graffitixr.common.azphalt.BrushSensorBinding
import com.hereliesaz.graffitixr.common.azphalt.BrushStamps
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

/** Source/Mix contract: deterministic source choice and replay-owned foreground/background colors. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ColorSourceMixTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() = RenderTestBase.stubNativeLibs()
    }

    @Test
    fun `plain source preserves historical foreground exactly`() {
        val brush = AzphaltBrush(name = "plain")
        val dab = BrushStamps.dabs(listOf(20f, 20f), 12f, brush, 7L).single()
        val foreground = Color.rgb(23, 91, 177)
        assertEquals(foreground, StampBrushRenderer.resolvedColor(foreground, Color.YELLOW, brush, dab))
    }

    @Test
    fun `gradient source uses mix endpoints`() {
        val foreground = Color.RED
        val background = Color.BLUE
        val fgBrush = AzphaltBrush(name = "fg", colorSource = BrushColorSource.GRADIENT, colorMix = 0f)
        val bgBrush = fgBrush.copy(name = "bg", colorMix = 1f)
        val fgDab = BrushStamps.dabs(listOf(10f, 10f), 8f, fgBrush, 11L).single()
        val bgDab = BrushStamps.dabs(listOf(10f, 10f), 8f, bgBrush, 11L).single()
        assertEquals(foreground, StampBrushRenderer.resolvedColor(foreground, background, fgBrush, fgDab))
        assertEquals(background, StampBrushRenderer.resolvedColor(foreground, background, bgBrush, bgDab))
    }

    @Test
    fun `uniform random source is deterministic without perturbing geometry`() {
        val randomBrush = AzphaltBrush(name = "random", spacing = 0.25f, colorSource = BrushColorSource.UNIFORM_RANDOM)
        val plainBrush = randomBrush.copy(colorSource = BrushColorSource.PLAIN)
        val path = listOf(4f, 10f, 60f, 10f)
        val a = BrushStamps.dabs(path, 12f, randomBrush, 1234L)
        val b = BrushStamps.dabs(path, 12f, randomBrush, 1234L)
        val plain = BrushStamps.dabs(path, 12f, plainBrush, 1234L)
        assertEquals(a.map { it.sourceRandom }, b.map { it.sourceRandom })
        assertTrue(a.map { it.sourceRandom }.distinct().size > 1)
        assertEquals(a.map { Triple(it.x, it.y, it.radius) }, plain.map { Triple(it.x, it.y, it.radius) })
    }

    @Test
    fun `pressure mix route overrides the base gradient coordinate`() {
        val brush = AzphaltBrush(
            name = "pressure mix",
            colorSource = BrushColorSource.GRADIENT,
            colorMix = 0.5f,
            dynamics = listOf(
                BrushSensorBinding(BrushSensor.PRESSURE, BrushParameter.MIX, outputMin = 0f, outputMax = 1f)
            ),
        )
        val builder = BrushSampleBuilder()
        val samples = listOf(
            builder.add(8f, 12f, 0L, pressure = 0f),
            builder.add(40f, 12f, 16L, pressure = 1f),
        )
        val dabs = BrushStamps.dynamicDabs(samples, 10f, brush, 55L)
        assertTrue(dabs.first().colorMix < 0.1f)
        assertTrue(dabs.last().colorMix > 0.8f)
    }

    @Test
    fun `replay uses snapshotted secondary colour rather than later UI colour`() = runTest {
        val engine = DrawingEngine(mockk<SlamManager>(relaxed = true))
        val size = IntSize(48, 32)
        val brush = AzphaltBrush(name = "gradient", hardness = 1f, colorSource = BrushColorSource.GRADIENT, colorMix = 1f)
        val command = StrokeCommand(
            path = listOf(Offset(8f, 16f), Offset(40f, 16f)),
            canvasSize = size,
            tool = Tool.BRUSH,
            brushSize = 10f,
            brushColor = Color.RED,
            secondaryBrushColor = Color.BLUE,
            intensity = 0.5f,
            stampBrush = brush,
            flow = 1f,
            seed = 9L,
        )
        fun base(): Bitmap = RenderTestBase.filled(size.width, size.height, Color.WHITE)
        val committed = engine.applySingleStroke(base(), command)
        val replayed = engine.composite(base(), listOf(command))
        assertEquals(committed.getPixel(24, 16), replayed.getPixel(24, 16))
        val p = replayed.getPixel(24, 16)
        assertTrue(Color.blue(p) > Color.red(p))
        assertNotEquals(Color.WHITE, p)
    }
}
