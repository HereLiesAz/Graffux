package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.SelectionOp
import com.hereliesaz.graffitixr.common.model.frame
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Subject isolation's wiring, with a fake segmenter standing in for ML Kit: the active layer's
 * pixels go to the segmenter, its confidence mask becomes the selection (traced, mapped to the
 * screen like the wand's, composed with Add/Remove), the busy spinner is up while it runs, an
 * unavailable model leaves the selection alone. The photo tools' Local "From selection" then takes
 * it as a mask like any other selection.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SubjectSelectionTest {

    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvas = IntSize(200, 200)

    /** Confident inside x in [left, right), y in [top, bottom) of whatever it is given. */
    private class BoxSegmenter(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) : SubjectSegmenter {
        var seen: Pair<Int, Int>? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun segment(bitmap: Bitmap): FloatArray {
            seen = bitmap.width to bitmap.height
            gate?.await()
            val w = bitmap.width
            val h = bitmap.height
            return FloatArray(w * h) { i ->
                val x = (i % w).toFloat() / w
                val y = (i / w).toFloat() / h
                val inX = x >= left && x < right
                val inY = y >= top && y < bottom
                if (inX && inY) 0.9f else 0.1f
            }
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        RenderTestBase.stubNativeLibs()
        vm = EditorViewModelFixture.build(dispatcher)
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvas))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun photo(w: Int, h: Int) =
        Layer(id = "photo", name = "Photo", bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))

    private fun useLayers(vararg layers: Layer, active: String) {
        vm.dispatchForTest(EditorIntent.SetLayers(layers.toList()))
        vm.dispatchForTest(EditorIntent.ActivateLayer(active))
    }

    /** The selection's bounding-box centre and size, in screen pixels. */
    private fun bounds(): Pair<Offset, Offset> {
        val pts = vm.uiState.value.selection!!.rings.flatMap { it.path }
        val l = pts.minOf { it.x }
        val r = pts.maxOf { it.x }
        val t = pts.minOf { it.y }
        val b = pts.maxOf { it.y }
        return Offset((l + r) / 2, (t + b) / 2) to Offset(r - l, b - t)
    }

    private fun screenOf(layer: Layer, x: Float, y: Float): Offset {
        val bmp = layer.bitmap!!
        return ImageProcessor.mapBitmapToScreen(
            listOf(Offset(x, y)), canvas.width, canvas.height, layer.frame(bmp.width, bmp.height),
            layer.scale, layer.offset, layer.rotationZ,
        ).first()
    }

    private fun near(expected: Offset, actual: Offset, tol: Float) =
        assertTrue("expected $expected, was $actual", (expected - actual).getDistance() <= tol)

    @Test
    fun `the subject becomes the selection, mapped like the wand's`() {
        val layer = photo(100, 100)
        useLayers(layer, active = "photo")
        val fake = BoxSegmenter(0.2f, 0.3f, 0.6f, 0.7f)
        vm.subjectSegmenter = fake

        vm.onSelectSubject()

        assertEquals(100 to 100, fake.seen)
        assertNotNull(vm.uiState.value.selection)
        val (centre, size) = bounds()
        // The box is bitmap x 20..60, y 30..70: centre (40, 50).
        near(screenOf(layer, 40f, 50f), centre, 2f)
        val corner = screenOf(layer, 60f, 70f) - screenOf(layer, 20f, 30f)
        near(corner, size, 2f)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `a large layer is segmented scaled down and traced back to full size`() {
        val layer = photo(2560, 1280)
        useLayers(layer, active = "photo")
        val fake = BoxSegmenter(0.5f, 0.25f, 0.75f, 0.75f)
        vm.subjectSegmenter = fake

        vm.onSelectSubject()

        assertEquals(1280 to 640, fake.seen)
        val (centre, _) = bounds()
        near(screenOf(layer, 1600f, 640f), centre, 2f)
    }

    @Test
    fun `the spinner is up while the segmenter runs`() {
        useLayers(photo(64, 64), active = "photo")
        val fake = BoxSegmenter(0.25f, 0.25f, 0.75f, 0.75f).apply { gate = CompletableDeferred() }
        vm.subjectSegmenter = fake

        vm.onSelectSubject()
        assertTrue(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.selection)

        fake.gate!!.complete(Unit)
        assertFalse(vm.uiState.value.isLoading)
        assertNotNull(vm.uiState.value.selection)
    }

    @Test
    fun `no model leaves the selection as it was`() {
        useLayers(photo(64, 64), active = "photo")
        vm.subjectSegmenter = SubjectSegmenter { null }

        vm.onSelectSubject()

        assertNull(vm.uiState.value.selection)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `Remove with nothing selected stays nothing, as the wand does`() {
        useLayers(photo(64, 64), active = "photo")
        vm.dispatchForTest(EditorIntent.SetSelectionOp(SelectionOp.REMOVE))
        vm.subjectSegmenter = BoxSegmenter(0.25f, 0.25f, 0.75f, 0.75f)

        vm.onSelectSubject()

        assertNull(vm.uiState.value.selection)
    }
}
