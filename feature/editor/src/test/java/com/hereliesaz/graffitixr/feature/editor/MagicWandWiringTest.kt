package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.model.SelectionRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Audit round 2, #13: the magic wand composes with the selection as it is when its result lands,
 * drops a result traced against a canvas that has since changed, and survives running out of memory.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MagicWandWiringTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvas = IntSize(200, 200)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        RenderTestBase.stubNativeLibs()
        vm = EditorViewModelFixture.build(dispatcher)
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvas))
        val layer = Layer(id = "p", name = "P", bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
        vm.dispatchForTest(EditorIntent.ActivateLayer("p"))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A filled square of the bitmap from [x0] to [x1] on both axes. */
    private fun square(x0: Int, x1: Int): (Bitmap, Int, Int, Int) -> BooleanArray? = { b, _, _, _ ->
        BooleanArray(b.width * b.height) { i -> (i % b.width) in x0 until x1 && (i / b.width) in x0 until x1 }
    }

    private val elsewhere = Selection(
        rings = listOf(SelectionRing(listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f)))),
        canvasSize = canvas,
    )

    @Test
    fun `an Add tap keeps a selection that changed while it was tracing`() {
        vm.dispatchForTest(EditorIntent.SetSelectionOp(SelectionOp.ADD))
        val trace = square(20, 40)
        vm.wandMask = { b, x, y, t ->
            // Another tap's region lands while this one is still working.
            vm.dispatchForTest(EditorIntent.SetSelection(elsewhere))
            trace(b, x, y, t)
        }
        vm.onAutoSelect(Offset(100f, 100f), canvas)
        val rings = vm.uiState.value.selection!!.rings
        assertTrue("the other region was dropped: $rings", rings.size >= 2)
    }

    @Test
    fun `a result traced against a resized canvas is dropped`() {
        val trace = square(20, 40)
        vm.wandMask = { b, x, y, t ->
            vm.dispatchForTest(EditorIntent.SetCanvasSize(IntSize(300, 120)))
            trace(b, x, y, t)
        }
        vm.onAutoSelect(Offset(100f, 100f), canvas)
        assertNull(vm.uiState.value.selection)
    }

    @Test
    fun `running out of memory leaves the selection alone instead of crashing`() {
        vm.wandMask = { _, _, _, _ -> throw OutOfMemoryError("flood") }
        vm.onAutoSelect(Offset(100f, 100f), canvas)
        assertNull(vm.uiState.value.selection)
        // And the wand still works afterwards.
        vm.wandMask = square(20, 40)
        vm.onAutoSelect(Offset(100f, 100f), canvas)
        assertNotNull(vm.uiState.value.selection)
        assertEquals(canvas, vm.uiState.value.selection!!.canvasSize)
    }
}
