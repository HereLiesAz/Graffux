package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A dropped stamp stroke used to say only "failed to paint (stamp)": engine creation's own
 * exception (the native loader's, wgpu's) was swallowed. It now names the stage and carries the cause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GpuStampCommitFailureTest {

    @Before
    fun setup() {
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } throws UnsatisfiedLinkError("libgraffux_wgpu_missing")
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `a failed engine creation names its stage and carries the underlying exception`() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val stroke = StrokeCommand(
            path = listOf(Offset(10f, 10f), Offset(40f, 40f)),
            canvasSize = IntSize(64, 64),
            tool = Tool.BRUSH,
            brushSize = 8f,
            brushColor = 0xFF000000.toInt(),
            intensity = 0.5f,
        )
        val brush = AzphaltBrush(name = "Solid", hardness = 1f, opacity = 1f)
        val thrown = runCatching {
            GpuStampCommit.paintStroke(bitmap, stroke, brush, stroke.path, 1f)
        }.exceptionOrNull()

        assertTrue("got $thrown", thrown is StrokeRenderException)
        assertTrue(thrown!!.message!!.contains("engine creation"))
        assertTrue(thrown.message!!.contains("libgraffux_wgpu_missing"))
        assertNotNull(thrown.cause)
        assertEquals("UnsatisfiedLinkError", thrown.cause!!.javaClass.simpleName)
    }

    @Test
    fun `a stamp on a rotated, scaled plane pose reports the same cause through applySingleStroke`() {
        // Plane/surface strokes commit through the same applySingleStroke; only the pose differs.
        // ImageProcessor loads its native library when first touched: let that happen before the
        // loader is made to fail, so the failure comes from the GPU engine's creation.
        every { NativeLibLoader.loadAll() } returns Unit
        com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.screenToBitmapScale(
            64, 64, com.hereliesaz.graffitixr.common.model.LayerFrame.of(64, 64), 1f,
        )
        every { NativeLibLoader.loadAll() } throws UnsatisfiedLinkError("libgraffux_wgpu_missing")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val stroke = StrokeCommand(
            path = listOf(Offset(10f, 10f), Offset(40f, 30f), Offset(50f, 50f)),
            canvasSize = IntSize(64, 64),
            tool = Tool.BRUSH,
            brushSize = 8f,
            brushColor = 0xFF000000.toInt(),
            intensity = 0.5f,
            layerScale = 0.5f,
            layerOffset = Offset(6f, -4f),
            layerRotationZ = 30f,
            stampBrush = AzphaltBrush(name = "Solid", hardness = 1f, opacity = 1f),
        )
        val engine = DrawingEngine(io.mockk.mockk(relaxed = true))
        val thrown = runCatching {
            kotlinx.coroutines.runBlocking { engine.applySingleStroke(bitmap, stroke) }
        }.exceptionOrNull()
        assertTrue("got $thrown", thrown is StrokeRenderException)
        assertTrue(thrown!!.message!!.contains("libgraffux_wgpu_missing"))
    }

    @Test
    fun `a stroke is painted on the adopted live engine and no second engine is created`() {
        // ImageProcessor loads its native library when first touched; then every NEW engine fails.
        every { NativeLibLoader.loadAll() } returns Unit
        com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.screenToBitmapScale(
            64, 64, com.hereliesaz.graffitixr.common.model.LayerFrame.of(64, 64), 1f,
        )
        every { NativeLibLoader.loadAll() } throws UnsatisfiedLinkError("a second engine was created")
        val adopted = io.mockk.mockk<com.hereliesaz.graffitixr.nativebridge.GpuStampEngine>()
        every { adopted.setStampMode(any()) } returns true
        every { adopted.stampResolvedDabs(any(), any(), any(), any()) } returns true
        every { adopted.readback(any()) } returns true
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val stroke = StrokeCommand(
            path = listOf(Offset(10f, 10f), Offset(40f, 30f), Offset(50f, 50f)),
            canvasSize = IntSize(64, 64),
            tool = Tool.BRUSH,
            brushSize = 8f,
            brushColor = 0xFF000000.toInt(),
            intensity = 0.5f,
            stampBrush = AzphaltBrush(name = "Solid", hardness = 1f, opacity = 1f),
        )
        val painted = kotlinx.coroutines.runBlocking {
            DrawingEngine(io.mockk.mockk(relaxed = true))
                .applySingleStroke(bitmap, stroke, adopted = AdoptedEngine(adopted) {})
        }
        assertNotNull(painted)
        io.mockk.verify(exactly = 1) { adopted.readback(any()) }
        io.mockk.verify(atLeast = 1) { adopted.stampResolvedDabs(any(), any(), any(), any()) }
        io.mockk.verify(exactly = 0) { adopted.upload(any()) } // seeded at the stroke's start
    }

    @Test
    fun `a failed adopted attempt releases the adopted engine before the retry builds a fresh one`() {
        val events = ArrayList<String>()
        every { NativeLibLoader.loadAll() } returns Unit
        com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor.screenToBitmapScale(
            64, 64, com.hereliesaz.graffitixr.common.model.LayerFrame.of(64, 64), 1f,
        )
        every { NativeLibLoader.loadAll() } answers {
            events += "fresh engine"
            throw UnsatisfiedLinkError("no second engine here")
        }
        val adopted = io.mockk.mockk<com.hereliesaz.graffitixr.nativebridge.GpuStampEngine>()
        every { adopted.setStampMode(any()) } returns true
        every { adopted.stampResolvedDabs(any(), any(), any(), any()) } returns false // the adopted attempt fails
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val stroke = StrokeCommand(
            path = listOf(Offset(10f, 10f), Offset(40f, 30f), Offset(50f, 50f)),
            canvasSize = IntSize(64, 64),
            tool = Tool.BRUSH,
            brushSize = 8f,
            brushColor = 0xFF000000.toInt(),
            intensity = 0.5f,
            stampBrush = AzphaltBrush(name = "Solid", hardness = 1f, opacity = 1f),
        )
        val thrown = runCatching {
            kotlinx.coroutines.runBlocking {
                DrawingEngine(io.mockk.mockk(relaxed = true))
                    .applySingleStroke(
                        bitmap, stroke, adopted = AdoptedEngine(adopted) { events += "adopted released" },
                    )
            }
        }.exceptionOrNull()
        assertTrue("got $thrown", thrown is StrokeRenderException)
        assertEquals(listOf("adopted released", "fresh engine"), events)
    }
}
