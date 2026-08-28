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
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionRing
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import com.hereliesaz.graffitixr.data.brush.CustomBrushRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Does each commit path actually route through the engine that replays it?**
 *
 * This is the gap the render tier could not reach and three shipped bugs lived in. Clone committed
 * an empty bitmap; the bucket fill and the blur each published an unfeathered result while their
 * replay feathered. Every one of them was a ViewModel path maintaining its own composite beside
 * `DrawingEngine`'s and drifting from it — invisible to a test of the engine alone, because the
 * engine was right in all three cases.
 *
 * So these tests drive the real ViewModel and compare the pixels it **publishes** against the pixels
 * a **replay of its own recorded command** produces. That is the property users experience as "the
 * layer changed the first time I undid", and it holds only if the path went through the engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CommitPathRoutingTest {

    // Unconfined, not Standard. A commit that hops to the real Dispatchers.Default (which
    // ImageProcessor does, internally) comes back through `withContext(dispatchers.main)`; on a
    // Standard dispatcher that continuation only runs when the scheduler is advanced, and the test
    // has already stopped advancing by the time the background work finishes. Unconfined runs it
    // wherever it lands, so awaitCommit below can actually observe the result.
    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: EditorViewModel

    private val canvas = IntSize(48, 48)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        RenderTestBase.stubNativeLibs()
        vm = EditorViewModelFixture.build(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /** Seeds one paint layer and a feathered selection, and reports the pre-edit pixels. */
    private fun seed(featherPx: Float = 8f): Bitmap {
        val base = RenderTestBase.filled(48, 48, Color.WHITE)
        val layer = Layer(id = "L", name = "Layer", bitmap = base)
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvas))
        vm.dispatchForTest(
            EditorIntent.SetSelection(
                Selection(
                    rings = listOf(
                        SelectionRing(SelectionGeometry.rectangle(Offset(12f, 12f), Offset(36f, 36f)))
                    ),
                    canvasSize = canvas,
                    featherPx = featherPx,
                )
            )
        )
        vm.setActiveColor(androidx.compose.ui.graphics.Color.Red)
        return base.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun publishedBitmap(): Bitmap =
        vm.uiState.value.layers.single { it.id == "L" }.bitmap!!

    /**
     * Waits for a commit that leaves the test dispatcher.
     *
     * `advanceUntilIdle` is not enough for every path: [ImageProcessor.applyToolToBitmap] wraps its
     * body in `withContext(Dispatchers.Default)` — the real one, not the injected provider — so any
     * tool routed through it finishes on a background thread the scheduler knows nothing about. The
     * paths that composite with a bare Canvas do stay on the test dispatcher, which is why only some
     * of these tests need this. Poll rather than pretend otherwise.
     */
    private fun awaitCommit(
        timeoutMs: Long = 5_000,
        message: String = "commit did not land",
        until: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            dispatcher.scheduler.advanceUntilIdle()
            if (until()) return
        }
        throw AssertionError("$message (waited ${timeoutMs}ms)")
    }

    private fun assertSamePixels(a: Bitmap, b: Bitmap, what: String) {
        var differing = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a.getPixel(x, y) != b.getPixel(x, y)) differing++
        }
        assertEquals("$what: $differing px differ between what was committed and what replays", 0, differing)
    }

    /**
     * Replays the commands the ViewModel recorded for the layer, from the pre-edit pixels, and
     * compares. Uses a DrawingEngine of this test's own — the same class the ViewModel uses, but a
     * separate instance, so this is a genuine second opinion rather than a shared code path.
     */
    private suspend fun assertCommitEqualsReplay(before: Bitmap, what: String) {
        val strokes = vm.recordedStrokesForTest("L")
        assertTrue("$what: nothing was recorded, so there is no replay to compare", strokes.isNotEmpty())
        val replayed = DrawingEngine(vm.slamManager).composite(before, strokes)
        assertSamePixels(publishedBitmap(), replayed, what)
    }

    @Test
    fun `colour fill inside a feathered selection commits what it replays`() = runTest(dispatcher) {
        val before = seed()
        vm.onColorFillSelection()
        awaitCommit { publishedBitmap().getPixel(24, 24) != before.getPixel(24, 24) }
        assertCommitEqualsReplay(before, "colour fill")
    }

    @Test
    fun `the paint bucket inside a feathered selection commits what it replays`() = runTest(dispatcher) {
        // One of the two paths that published a hard edge while its replay feathered.
        val before = seed()
        vm.setActiveTool(Tool.FILL)
        vm.onFillTap(Offset(24f, 24f), canvas)
        awaitCommit { publishedBitmap().getPixel(24, 24) != before.getPixel(24, 24) }
        assertCommitEqualsReplay(before, "paint bucket")
    }

    @Test
    fun `clear inside a feathered selection commits what it replays`() = runTest(dispatcher) {
        val before = seed()
        vm.onClearLayer()
        dispatcher.scheduler.advanceUntilIdle()
        assertCommitEqualsReplay(before, "clear")
    }

    @Test
    fun `a feathered selection actually changes the committed pixels`() = runTest(dispatcher) {
        // Guards the guard. Every agreement test above would also pass if the feather did nothing
        // at all, so prove a feathered fill differs from a hard-edged one before trusting them.
        //
        // A fresh ViewModel per case rather than re-seeding one: re-running @Before mid-test leaves
        // the first instance's collectors on the same dispatcher, and the second seed then races
        // them. That is how this test first failed — on its own setup, not on the code.
        fun startFill(featherPx: Float): EditorViewModel {
            val v = EditorViewModelFixture.build(dispatcher)
            val bmp = RenderTestBase.filled(48, 48, Color.WHITE)
            v.dispatchForTest(EditorIntent.SetLayers(listOf(Layer(id = "L", name = "L", bitmap = bmp))))
            v.onLayerActivated("L")
            v.dispatchForTest(EditorIntent.SetCanvasSize(canvas))
            v.dispatchForTest(
                EditorIntent.SetSelection(
                    Selection(
                        rings = listOf(
                            SelectionRing(SelectionGeometry.rectangle(Offset(12f, 12f), Offset(36f, 36f)))
                        ),
                        canvasSize = canvas,
                        featherPx = featherPx,
                    )
                )
            )
            // A colour that contrasts with the base. Filling the default colour onto a white layer
            // produces no visible difference anywhere, so the test would compare two identical
            // bitmaps and report that feathering does nothing — which is how it first failed.
            v.setActiveColor(androidx.compose.ui.graphics.Color.Red)
            v.onColorFillSelection()
            return v
        }
        fun published(v: EditorViewModel) = v.uiState.value.layers.single { it.id == "L" }.bitmap!!

        val hardVm = startFill(0f)
        awaitCommit { published(hardVm).getPixel(24, 24) != Color.WHITE }
        val hard = published(hardVm).copy(Bitmap.Config.ARGB_8888, false)
        val softVm = startFill(10f)
        awaitCommit { published(softVm).getPixel(24, 24) != Color.WHITE }
        val soft = published(softVm)

        var differing = 0
        for (y in 0 until 48) for (x in 0 until 48) {
            if (hard.getPixel(x, y) != soft.getPixel(x, y)) differing++
        }
        assertTrue("feathering must change the result; $differing px differed", differing > 0)
    }

    @Test
    fun `a clone stroke publishes pixels rather than an empty layer`() = runTest(dispatcher) {
        // Clone committed nothing at all: its live paint is blank by design, and no commit path ran
        // the composite that fills it in. An agreement test alone would have passed — both sides
        // were empty — so this asserts it paints, then that it agrees.
        val art = RenderTestBase.filled(48, 48, Color.WHITE).also { b ->
            for (y in 0 until 16) for (x in 0 until 16) b.setPixel(x, y, Color.RED)
        }
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(Layer(id = "L", name = "L", bitmap = art))))
        vm.onLayerActivated("L")
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvas))
        val before = art.copy(Bitmap.Config.ARGB_8888, false)

        vm.setActiveTool(Tool.CLONE)
        vm.onSetCloneSource(Offset(8f, 8f))
        vm.onStrokeStart(Offset(32f, 32f), canvas)
        vm.onStrokePoint(Offset(38f, 32f))
        advanceUntilIdle()
        vm.onStrokeEnd()
        advanceUntilIdle()
        awaitCommit { publishedBitmap().getPixel(32, 32) != before.getPixel(32, 32) }

        val committed = publishedBitmap()
        assertNotNull(committed)
        var painted = 0
        for (y in 0 until 48) for (x in 0 until 48) {
            if (committed.getPixel(x, y) != before.getPixel(x, y)) painted++
        }
        assertTrue("clone must put pixels down on commit, not only on replay", painted > 0)

        // The colour, not merely that something changed. "Something changed" is far too weak here:
        // with the offset applied the wrong way round, clone still altered pixels — it painted the
        // raw stroke mask, which is solid BLACK — so a change-detecting assertion passed against a
        // tool that was copying from the mirror-opposite point and smearing black everywhere the
        // source failed to reach. Only asserting *what* landed catches either.
        assertEquals(
            "the stroke should carry the source colour, not the stroke mask",
            Color.RED,
            committed.getPixel(32, 32),
        )
        // And nothing black anywhere: the source is red-on-white, so a black pixel can only be the
        // mask leaking through where the shifted source did not reach.
        for (y in 0 until 48) for (x in 0 until 48) {
            assertTrue(
                "black at $x,$y means the stroke mask leaked instead of the source",
                committed.getPixel(x, y) != Color.BLACK,
            )
        }
        assertCommitEqualsReplay(before, "clone")
    }

    /**
     * The tools with no live paint all share one branch in `onStrokeEnd`, selected by membership of
     * a set. That is exactly the kind of condition a new tool falls out of silently: leave one out
     * and it paints a translucent black line (its transparent paint never runs) and commits nothing,
     * or it never reaches the engine and the layer changes on the first undo. Driving each one
     * through the real ViewModel is the only place that shows up.
     *
     * One test per tool rather than a loop over the three. A loop shares a `runTest` scope across
     * iterations, and these commits finish on a real background thread — the second tool's commit
     * did not land inside the poll window once the first iteration had already run a replay through
     * `Dispatchers.Default` on the same scope. Separate tests also say which tool broke without the
     * failure message having to name it.
     */
    private suspend fun assertResampledToolRoutesThroughEngine(tool: Tool) {
        val toolVm = EditorViewModelFixture.build(dispatcher)
        // A hard step between two mid-greys: an edge for each of them to bite on, since flat colour
        // gives blur and sharpen nothing to do and smudge nothing to carry.
        //
        // Both details are load-bearing, and both were learned the hard way. **Mid-greys**, because
        // sharpen cannot move a pixel already at 0 or 255 — original + k × (original − blurred)
        // clamps — so a black-and-white edge leaves it nowhere to overshoot. And a **step** rather
        // than a ramp, because a linear ramp is reproduced almost exactly by the bilinear
        // down-and-up the blur is built from, so the difference sharpen works on vanishes across it.
        val art = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { b ->
            for (y in 0 until 48) for (x in 0 until 48) {
                val v = if (x < 24) 70 else 190
                b.setPixel(x, y, Color.argb(255, v, v, v))
            }
        }
        toolVm.dispatchForTest(EditorIntent.SetLayers(listOf(Layer(id = "L", name = "L", bitmap = art))))
        toolVm.onLayerActivated("L")
        toolVm.dispatchForTest(EditorIntent.SetCanvasSize(canvas))
        val before = art.copy(Bitmap.Config.ARGB_8888, false)

        toolVm.setActiveTool(tool)
        toolVm.onStrokeStart(Offset(12f, 24f), canvas)
        toolVm.onStrokePoint(Offset(36f, 24f))
        toolVm.onStrokeEnd()

        fun published(): Bitmap = toolVm.uiState.value.layers.single { it.id == "L" }.bitmap!!
        fun changedPixels(after: Bitmap): Int =
            (0 until 48).sumOf { y -> (0 until 48).count { x -> after.getPixel(x, y) != before.getPixel(x, y) } }
        awaitCommit(message = "$tool published no change") { changedPixels(published()) > 0 }

        // It painted the artwork rather than the stroke mask. Each of these draws its mask with a
        // black-by-default Paint, so a black pixel on an image whose darkest value is 70 can only be
        // that mask leaking through — the failure mode Clone actually shipped with.
        val committed = published()
        for (y in 0 until 48) for (x in 0 until 48) {
            assertTrue(
                "$tool: black at $x,$y means the stroke mask leaked instead of the result",
                committed.getPixel(x, y) != Color.BLACK,
            )
        }

        val strokes = toolVm.recordedStrokesForTest("L")
        assertTrue("$tool: nothing recorded, so it never went through the engine", strokes.isNotEmpty())
        assertEquals("$tool: the recorded command must name the tool it was", tool, strokes.single().tool)
        assertSamePixels(committed, DrawingEngine(toolVm.slamManager).composite(before, strokes), "$tool")
    }

    @Test
    fun `blur commits through the engine`() = runTest(dispatcher) {
        assertResampledToolRoutesThroughEngine(Tool.BLUR)
    }

    @Test
    fun `sharpen commits through the engine`() = runTest(dispatcher) {
        assertResampledToolRoutesThroughEngine(Tool.SHARPEN)
    }

    @Test
    fun `smudge commits through the engine`() = runTest(dispatcher) {
        assertResampledToolRoutesThroughEngine(Tool.SMUDGE)
    }
    /**
     * Liquify's commit went through the engine's replay path, not around it.
     *
     * It used to bake the warp itself, right there in `onStrokeEnd`. That bypassed `DrawingEngine`
     * entirely, so the committed warp covered the whole layer while the replay confined it to the
     * selection — the layer changed the first time it was undone. The other three commit paths in
     * this file were each fixed for exactly this; Liquify was the one nobody had driven.
     */
    @Test
    fun `liquify commits what it replays, and stays inside the selection`() = runTest(dispatcher) {
        val before = seed()
        vm.setActiveTool(Tool.LIQUIFY)
        vm.onStrokeStart(Offset(20f, 24f), canvas)
        vm.onStrokePoint(Offset(30f, 24f))
        advanceUntilIdle()
        vm.onStrokeEnd()
        advanceUntilIdle()

        // The fixture's SlamManager is a relaxed mock, so the warp itself is a no-op here and the
        // committed pixels equal the base. That is fine and still worth asserting: what is under
        // test is that the commit *routed through the engine*, which the recorded command and the
        // agreement below establish. A bake done inline would have produced a bitmap the replay
        // could not reproduce.
        assertCommitEqualsReplay(before, "liquify")
        val strokes = vm.recordedStrokesForTest("L")
        assertEquals("the recorded command must be a liquify", Tool.LIQUIFY, strokes.single().tool)
        assertNotNull(
            "the command must carry the selection, or the replay cannot confine the warp",
            strokes.single().selection,
        )
    }

    /**
     * The bug this whole file exists to catch, but for the tool most users actually paint with.
     * `commitStampStroke` used to hand-roll a bare `StampBrushRenderer.paintStroke` call that never
     * consulted `brush.dynamics` at all: a pressure-driven brush tapered correctly in the live
     * preview, then lost the taper entirely the instant the stroke committed, because the commit
     * render used static (non-dynamic) dab placement while replay (through `DrawingEngine`, which
     * does honor `dynamics`) reproduces the tapered stroke correctly. That drift is exactly what
     * `assertCommitEqualsReplay` is built to catch for every other tool in this file -- the stamp
     * brush was the one path nobody had driven through it.
     */
    @Test
    fun `a stamp brush with pressure dynamics commits what it replays`() = runTest(dispatcher) {
        val brush = AzphaltBrush(
            name = "Taper",
            hardness = 1f,
            opacity = 1f,
            dynamics = listOf(
                BrushSensorBinding(
                    sensor = BrushSensor.PRESSURE,
                    parameter = BrushParameter.SIZE,
                    outputMin = 0.2f,
                    outputMax = 1f,
                ),
            ),
        )
        val brushes = mockk<CustomBrushRepository>(relaxed = true) {
            every { this@mockk.brushes } returns MutableStateFlow(emptyList())
            every { load("taper") } returns brush
        }
        val stampVm = EditorViewModelFixture.build(dispatcher, brushes)
        val base = RenderTestBase.filled(48, 48, Color.WHITE)
        val layer = Layer(id = "L", name = "Layer", bitmap = base)
        stampVm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
        stampVm.onLayerActivated("L")
        stampVm.dispatchForTest(EditorIntent.SetCanvasSize(canvas))
        stampVm.selectCustomBrush("taper")
        stampVm.setActiveTool(Tool.BRUSH)
        // A colour that contrasts with the white base -- painting the default white-on-white would
        // commit real pixels with no visible change, and awaitCommit would never see it.
        stampVm.setActiveColor(androidx.compose.ui.graphics.Color.Red)
        val before = base.copy(Bitmap.Config.ARGB_8888, false)

        // Varying pressure across the drag so a dynamics-aware render (dynamicDabs) actually
        // produces different dab sizes than a static one (BrushStamps.dabs) would.
        stampVm.onStrokeStart(BrushSample(12f, 24f, pressure = 0.1f, uptimeMillis = 0L), canvas)
        stampVm.onStrokePoint(BrushSample(24f, 24f, pressure = 0.6f, uptimeMillis = 16L))
        stampVm.onStrokePoint(BrushSample(36f, 24f, pressure = 1f, uptimeMillis = 32L))
        stampVm.onStrokeEnd()

        fun published(): Bitmap = stampVm.uiState.value.layers.single { it.id == "L" }.bitmap!!
        awaitCommit(message = "stamp stroke published no change") {
            published().getPixel(24, 24) != before.getPixel(24, 24)
        }

        val strokes = stampVm.recordedStrokesForTest("L")
        assertTrue("nothing was recorded, so there is no replay to compare", strokes.isNotEmpty())
        val replayed = DrawingEngine(stampVm.slamManager).composite(before, strokes)
        assertSamePixels(published(), replayed, "stamp brush with pressure dynamics")
    }
}