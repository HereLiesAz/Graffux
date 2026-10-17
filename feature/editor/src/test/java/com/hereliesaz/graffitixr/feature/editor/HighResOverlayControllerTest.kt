package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.LayerFrame
import com.hereliesaz.graffitixr.common.model.Tool
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Debounce, cancellation and invalidation of [HighResOverlayController] with a fake renderer. */
@OptIn(ExperimentalCoroutinesApi::class)
class HighResOverlayControllerTest {

    private val key1 = OverlayContentKey(1, 1, 1000, 2000, 1000, 2000, 0, 0)
    private val key2 = key1.copy(generation = 2)

    private fun layer(key: OverlayContentKey) =
        OverlayLayerInput("a", key, LayerFrame(1000, 2000), 1f, Offset.Zero, 0f, true)

    private fun inputs(zoom: Float = 2f, key: OverlayContentKey = key1, live: String? = null) =
        OverlayInputs(zoom, Offset.Zero, 0f, 1000, 2000, listOf(layer(key)), live, false)

    private val stroke = StrokeCommand(
        path = listOf(Offset(1f, 1f)), canvasSize = IntSize(1000, 2000), tool = Tool.BRUSH,
        brushSize = 10f, brushColor = 0, intensity = 1f,
    )

    private class Fixture(scope: TestScope, strokes: List<StrokeCommand>) {
        val renders = ArrayList<HighResRenderJob>()
        val base = mockk<Bitmap>(relaxed = true).also {
            io.mockk.every { it.width } returns 1000
            io.mockk.every { it.height } returns 2000
        }
        val controller = HighResOverlayController(
            scope = scope,
            renderDispatcher = StandardTestDispatcher(scope.testScheduler),
            source = object : HighResOverlaySource {
                override fun base(layerId: String) = base
                override fun strokes(layerId: String) = strokes
            },
            renderer = { job -> renders += job; mockk(relaxed = true) },
        )
        val flow = MutableSharedFlow<OverlayInputs>(extraBufferCapacity = 16)
    }

    @Test
    fun rendersOnlyAfterTheDebounce() = runTest {
        val f = Fixture(this, listOf(stroke))
        val job = f.controller.start(f.flow)
        runCurrent()
        f.flow.emit(inputs())
        advanceTimeBy(HighResOverlayConfig.DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(0, f.renders.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, f.renders.size)
        assertEquals(1, f.controller.overlays.value.size)
        job.cancel()
    }

    @Test
    fun newInputRestartsTheDebounce() = runTest {
        val f = Fixture(this, listOf(stroke))
        val job = f.controller.start(f.flow)
        runCurrent()
        f.flow.emit(inputs())
        advanceTimeBy(200)
        f.flow.emit(inputs(zoom = 2.5f))
        advanceTimeBy(200)
        runCurrent()
        assertEquals(0, f.renders.size)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, f.renders.size)
        job.cancel()
    }

    @Test
    fun nothingAtOrBelowTheTriggerZoom() = runTest {
        val f = Fixture(this, listOf(stroke))
        val job = f.controller.start(f.flow)
        runCurrent()
        f.flow.emit(inputs(zoom = 1.5f))
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(0, f.renders.size)
        assertTrue(f.controller.overlays.value.isEmpty())
        job.cancel()
    }

    @Test
    fun contentChangeDropsTheOverlayAtOnceAndZoomOutClearsIt() = runTest {
        val f = Fixture(this, listOf(stroke))
        val job = f.controller.start(f.flow)
        runCurrent()
        f.flow.emit(inputs())
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, f.controller.overlays.value.size)
        f.flow.emit(inputs(key = key2))
        runCurrent()
        assertTrue("dropped before any new render", f.controller.overlays.value.isEmpty())
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, f.controller.overlays.value.size)
        f.flow.emit(inputs(zoom = 1f, key = key2))
        runCurrent()
        assertTrue(f.controller.overlays.value.isEmpty())
        job.cancel()
    }

    @Test
    fun liveStrokeAndNonReplayableStrokesBuildNothing() = runTest {
        val f = Fixture(this, listOf(stroke, stroke.copy(tool = Tool.SMUDGE)))
        val job = f.controller.start(f.flow)
        runCurrent()
        f.flow.emit(inputs())
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(0, f.renders.size)
        val g = Fixture(this, listOf(stroke))
        val job2 = g.controller.start(g.flow)
        runCurrent()
        g.flow.emit(inputs(live = "a"))
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(0, g.renders.size)
        job.cancel()
        job2.cancel()
    }

    @Test
    fun memoryPressureDropsAndSuppressesUntilZoomOut() = runTest {
        val f = Fixture(this, listOf(stroke))
        val job = f.controller.start(f.flow)
        runCurrent()
        f.flow.emit(inputs())
        advanceTimeBy(400)
        runCurrent()
        f.controller.onMemoryPressure()
        assertTrue(f.controller.overlays.value.isEmpty())
        f.flow.emit(inputs(zoom = 3f))
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, f.renders.size) // only the render from before the pressure
        f.flow.emit(inputs(zoom = 1f))
        runCurrent()
        f.flow.emit(inputs(zoom = 3f))
        advanceTimeBy(400)
        runCurrent()
        assertEquals(2, f.renders.size)
        job.cancel()
    }
}
