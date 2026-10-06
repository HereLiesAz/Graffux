package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.Tool
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression for strokes vanishing on finger-up: every commit path begins `layer.bitmap ?: return`,
 * and the live preview never touches the layer, so a raster layer whose bitmap never arrived painted
 * live and then dropped the stroke. A stroke start now gives such a layer a blank bitmap; layers
 * that cannot hold paint stay as they are.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PaintLayerWithoutBitmapTest {

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

    private fun seed(layer: Layer) {
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
        vm.onLayerActivated(layer.id)
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvas))
        vm.setActiveTool(Tool.BRUSH)
    }

    private fun bitmapOf(id: String) = vm.uiState.value.layers.single { it.id == id }.bitmap

    @Test
    fun `a stroke on a raster layer with no bitmap gives it one`() {
        seed(Layer(id = "L", name = "Layer"))
        vm.onStrokeStart(Offset(10f, 10f), canvas)
        assertNotNull(bitmapOf("L"))
    }

    @Test
    fun `a group layer is left without a bitmap`() {
        seed(Layer(id = "G", name = "Group", type = LayerType.GROUP))
        vm.onStrokeStart(Offset(10f, 10f), canvas)
        assertNull(bitmapOf("G"))
    }
}
