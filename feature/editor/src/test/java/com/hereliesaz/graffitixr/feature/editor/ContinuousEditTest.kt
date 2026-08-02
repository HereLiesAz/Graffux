package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.hereliesaz.graffitixr.common.model.ShapeKind
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **One drag of a rail slider is one undoable edit.**
 *
 * It used to be one per emitted sample. Every value a slider produced pushed its own history entry
 * and launched its own un-debounced project write, so dragging Opacity across a shape layer buried
 * the undo stack under a frame-by-frame replay of the drag — Undo then walked back one slider frame
 * at a time — and wrote the project manifest dozens of times on the way.
 *
 * The panel sliders never had this problem because Material gives them `onValueChangeFinished` to
 * bracket with. `azRailSlider` gives no such callback, so the rail's sliders close their own bracket
 * on an idle timeout, and that is what these tests hold in place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ContinuousEditTest {

    // Standard, not Unconfined: these tests turn on being able to advance a virtual clock past the
    // idle window without any real waiting, which needs a scheduler that defers.
    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: EditorViewModel

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

    private fun undoCount() = vm.uiState.value.undoCount

    @Test
    fun `one slider drag is one undo entry, not one per sample`() = runTest(dispatcher) {
        vm.onAddShapeLayer(ShapeKind.RECTANGLE)
        advanceUntilIdle()
        val before = undoCount()

        // Twenty samples, as a real drag emits — well inside the idle window of each other.
        repeat(20) { i ->
            vm.setActiveColor(Color.Red.copy(alpha = 1f - i * 0.02f))
            advanceTimeBy(16)          // ~one frame
        }
        advanceUntilIdle()

        assertEquals(
            "a 20-sample drag must leave exactly one undoable edit",
            before + 1, undoCount(),
        )
    }

    /** Letting go and dragging again is a second edit — the bracket must actually close. */
    @Test
    fun `a second drag after a pause is a second undo entry`() = runTest(dispatcher) {
        vm.onAddShapeLayer(ShapeKind.RECTANGLE)
        advanceUntilIdle()
        val before = undoCount()

        repeat(5) { vm.setActiveColor(Color.Red.copy(alpha = 0.9f - it * 0.05f)); advanceTimeBy(16) }
        advanceUntilIdle()            // past the idle window: the run closes

        repeat(5) { vm.setActiveColor(Color.Blue.copy(alpha = 0.5f - it * 0.05f)); advanceTimeBy(16) }
        advanceUntilIdle()

        assertEquals("two separate drags are two edits", before + 2, undoCount())
    }

    /** The last value of the drag is the one that survives — coalescing must not lose the end. */
    @Test
    fun `the final value of a drag is what is kept`() = runTest(dispatcher) {
        vm.onAddShapeLayer(ShapeKind.RECTANGLE)
        advanceUntilIdle()

        repeat(10) { i -> vm.setActiveColor(Color.Red.copy(alpha = 1f - i * 0.05f)); advanceTimeBy(16) }
        advanceUntilIdle()

        val expected = Color.Red.copy(alpha = 1f - 9 * 0.05f)
        assertEquals("the active colour must be the last sample", expected, vm.uiState.value.activeColor)
        val shape = vm.uiState.value.layers.first { it.shapes.isNotEmpty() }.shapes.single()
        assertEquals(
            "the shape must carry the last sample too",
            expected.toArgb().toLong() and 0xFFFFFFFFL, shape.fillArgb,
        )
    }

    /** Undo after a drag returns to before the drag — not to the middle of it. */
    @Test
    fun `undo after a drag reverts the whole drag`() = runTest(dispatcher) {
        vm.onAddShapeLayer(ShapeKind.RECTANGLE)
        advanceUntilIdle()
        val original = vm.uiState.value.layers.first { it.shapes.isNotEmpty() }.shapes.single().fillArgb

        repeat(10) { i -> vm.setActiveColor(Color.Green.copy(alpha = 1f - i * 0.05f)); advanceTimeBy(16) }
        advanceUntilIdle()
        assertTrue(
            "the drag must have changed something to undo",
            vm.uiState.value.layers.first { it.shapes.isNotEmpty() }.shapes.single().fillArgb != original,
        )

        vm.onUndoClicked()
        advanceUntilIdle()

        assertEquals(
            "one undo must land before the drag, not one sample into it",
            original,
            vm.uiState.value.layers.first { it.shapes.isNotEmpty() }.shapes.single().fillArgb,
        )
    }
}
