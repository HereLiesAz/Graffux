package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class DrawingEngineTest {

    private val slamManager: SlamManager = mockk(relaxed = true)
    private lateinit var engine: DrawingEngine

    @Before
    fun setup() {
        // ImageProcessor's init block calls NativeLibLoader.loadAll() (Android-arm .so) — no-op it.
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        mockkObject(ImageProcessor)
        every {
            ImageProcessor.mapScreenToBitmap(any(), any(), any(), any(), any(), any(), any(), any())
        } returns emptyList<Offset>()
        engine = DrawingEngine(slamManager)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun bitmap(): Bitmap = mockk(relaxed = true) {
        every { width } returns 10
        every { height } returns 10
        every { copy(any(), any()) } returns this
    }

    private fun stroke(tool: Tool = Tool.NONE) = StrokeCommand(
        path = listOf(Offset.Zero),
        canvasSize = IntSize(10, 10),
        tool = tool,
        brushSize = 5f,
        brushColor = 0,
        intensity = 0.5f,
    )

    @Test
    fun `composite with no strokes copies the base rather than handing it back`() = runTest {
        // The property that matters is that composite() COPIES. Its own KDoc explains why at
        // length: callers publish the result as the layer's live bitmap, so returning `base` itself
        // would alias LayerStore's pristine copy into the UI and let the next in-place stroke
        // corrupt the one thing every rebuild and undo depends on.
        //
        // This used to assert `assertSame(base, result)` — the exact opposite — and passed only
        // because the mock's copy() is stubbed to return itself. It would have gone on passing if
        // the copy were deleted. Assert the call instead, which is the part the mock can actually
        // witness.
        val base = bitmap()
        engine.composite(base, emptyList())
        verify(exactly = 1) { base.copy(any(), any()) }
    }

    @Test
    fun `composite routes a LIQUIFY stroke through SlamManager`() = runTest {
        val base = bitmap()
        engine.composite(base, listOf(stroke(Tool.LIQUIFY)))

        verify { slamManager.prepareLiquify(any()) }
        verify { slamManager.applyLiquify(any(), any(), any()) }
        verify { slamManager.bakeLiquify(any()) }
    }

    @Test
    fun `composite throws when a stroke cannot be painted instead of returning the layer without it`() = runTest {
        // No GPU in a JVM test: the retouch stroke's engine cannot start. composite used to hand back
        // the strokes before it as if the replay had succeeded, and undo published that truncated
        // layer. It must fail so the caller keeps the layer as it was.
        val base = bitmap()
        val thrown = runCatching { engine.composite(base, listOf(stroke(Tool.BLUR))) }.exceptionOrNull()
        org.junit.Assert.assertTrue("got $thrown", thrown is StrokeRenderException)
    }

    @Test
    fun `applySingleStroke throws when the stroke cannot be painted`() = runTest {
        val thrown = runCatching { engine.applySingleStroke(bitmap(), stroke(Tool.BLUR)) }.exceptionOrNull()
        org.junit.Assert.assertTrue("got $thrown", thrown is StrokeRenderException)
    }
}
