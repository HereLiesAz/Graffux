@file:Suppress("MaxLineLength", "MagicNumber", "NestedBlockDepth")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.Selection
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SurfaceHull
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.common.scene.SurfaceView
import com.hereliesaz.graffitixr.common.util.SelectionGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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

/** Wand and move of selected pixels on a multi-view surface, through the view-model (spec/3d-space.md). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SurfaceSelectionTest {
    @Suppress("EXPERIMENTAL_API_USAGE")
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: EditorViewModel
    private val canvas = IntSize(1700, 1200)
    private val w = canvas.width.toFloat()
    private val h = canvas.height.toFloat()
    private val camera = SceneCamera(yaw = 0.6f, pitch = 0.3f)

    private fun outline(sweep: FloatArray, place: (Float, Float) -> FloatArray) = SurfaceProfile(
        listOf(300f to 200f, 700f to 200f, 700f to 600f, 300f to 600f).flatMap { place(it.first, it.second).toList() },
        sweep[0], sweep[1], sweep[2],
    )

    private val cube = outline(floatArrayOf(0f, 0f, 1f)) { x, y -> floatArrayOf(x, y, 0f) }.copy(
        side = outline(floatArrayOf(1f, 0f, 0f)) { z, y -> floatArrayOf(0f, y, z - 300f) },
        top = outline(floatArrayOf(0f, 1f, 0f)) { x, z -> floatArrayOf(x, 0f, z - 300f) },
    )
    private val hull = SurfaceHull.of(cube)!!
    private val colours = mapOf(SurfaceView.FRONT to Color.RED, SurfaceView.SIDE to Color.GREEN, SurfaceView.TOP to Color.BLUE)

    private fun image(view: SurfaceView, paint: Int?) = Layer(
        id = "p${view.name}", name = view.name, parentId = "g", isVisible = false,
        surfaceViewOf = "p", surfaceView = view,
        bitmap = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888).also { if (paint != null) it.eraseColor(paint) },
    )

    private fun load(paint: Map<SurfaceView, Int>) {
        val group = Layer("g", "G", type = LayerType.GROUP, surface = cube)
        val leaf = Layer("p", "P", parentId = "g")
        val images = SurfaceView.entries.map { image(it, paint[it]) }
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(group, leaf) + images))
        vm.dispatchForTest(EditorIntent.ActivateLayer("p"))
        images.forEach { vm.putLayerBaseForTest(it.id, it.bitmap!!.copy(Bitmap.Config.ARGB_8888, true)) }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        RenderTestBase.stubNativeLibs()
        vm = EditorViewModelFixture.build(dispatcher)
        vm.dispatchForTest(EditorIntent.SetCanvasSize(canvas))
        vm.setSceneCameraForTest(camera)
    }

    @After
    fun tearDown() {
        // Three canvas-sized images per test: give the heap back to the rest of the suite.
        vm.uiState.value.layers.forEach { l -> l.bitmap?.takeIf { !it.isRecycled }?.recycle() }
        SurfaceView.entries.forEach { v -> vm.layerBaseForTest("p${v.name}")?.takeIf { !it.isRecycled }?.recycle() }
        Dispatchers.resetMain()
    }

    private val owner get() = SurfaceMath.ownerOf(vm.uiState.value.layers, "p")!!

    private fun touches() = buildList {
        var y = 0f
        while (y < h) {
            var x = 0f
            while (x < w) {
                SurfaceMath.worldToViewFlat(x, y, owner, camera, w, h, hull)?.let { add(Offset(x, y) to it) }
                x += 20f
            }
            y += 20f
        }
    }

    /** Not within 15px of a place where the face under the viewer changes. */
    private fun interior(at: Offset, f: SurfaceMath.ViewFlat) = listOf(Offset(15f, 0f), Offset(-15f, 0f), Offset(0f, 15f), Offset(0f, -15f)).all {
        val o = SurfaceMath.worldToViewFlat(at.x + it.x, at.y + it.y, owner, camera, w, h, hull)
        o != null && o.view == f.view && o.region == f.region
    }

    private fun Selection.holds(at: Offset) = SelectionGeometry.contains(this, at)

    @Test
    fun `the wand floods across a crease into the next face and stops at a different colour`() {
        load(emptyMap())
        val ts = touches().filter { it.second.region == 0 }
        val views = ts.map { it.second.view }.distinct()
        assertTrue("views $views", views.size >= 2)
        vm.wandMask = { b, _, _, _ -> BooleanArray(b.width * b.height) { true } }
        val tap = ts.first { it.second.view == views[0] }.second
        vm.onViewAutoSelect("p", tap, canvas)
        val all = vm.uiState.value.selection!!
        for (view in views) {
            val own = ts.filter { it.second.view == view && interior(it.first, it.second) }
            assertTrue("$view reached", own.count { all.holds(it.first) } > own.size / 2)
        }
        assertTrue("corner", !all.holds(Offset(2f, 2f)))
        // A face painted another colour is not entered.
        vm.onClearSelection()
        load(mapOf(views[1] to Color.RED))
        vm.wandMask = { b, x, y, _ ->
            val c = b.getPixel(x, y)
            BooleanArray(b.width * b.height) { b.getPixel(it % b.width, it / b.width) == c }
        }
        vm.onViewAutoSelect("p", tap, canvas)
        val only = vm.uiState.value.selection!!
        val other = ts.filter { it.second.view == views[1] && interior(it.first, it.second) }
        val n = other.count { only.holds(it.first) }
        assertTrue("stopped $n of ${other.size}", n < other.size / 20)
    }

    @Test
    fun `moving a selection across a crease is one undo step`() {
        load(colours)
        val ts = touches().filter { it.second.region == 0 }
        val box = Selection.ofPolygon(
            listOf(Offset(ts.minOf { it.first.x } - 5, ts.minOf { it.first.y } - 5), Offset(ts.maxOf { it.first.x } + 5, ts.minOf { it.first.y } - 5), Offset(ts.maxOf { it.first.x } + 5, ts.maxOf { it.first.y } + 5), Offset(ts.minOf { it.first.x } - 5, ts.maxOf { it.first.y } + 5)),
            canvas,
        )
        vm.dispatchForTest(EditorIntent.SetSelection(box))
        vm.onSurfaceSelectionMove("p", Offset(60f, 0f))
        val after = SurfaceView.entries.associateWith { vm.uiState.value.layers.first { l -> l.id == "p${it.name}" }.bitmap!! }
        // Pixels crossed into another image: some image now holds a colour that was another view's.
        val foreign = SurfaceView.entries.sumOf { v ->
            val bmp = after.getValue(v)
            var n = 0
            for (y in 0 until bmp.height step 7) for (x in 0 until bmp.width step 7) {
                val c = bmp.getPixel(x, y)
                if (c != colours.getValue(v) && c != Color.TRANSPARENT) n++
            }
            n
        }
        assertTrue("pixels crossed into a neighbouring image ($foreign)", foreign > 0)
        assertEquals(box.translated(Offset(60f, 0f)), vm.uiState.value.selection)
        vm.onUndoClicked()
        for (v in SurfaceView.entries) {
            val now = vm.uiState.value.layers.first { it.id == "p${v.name}" }.bitmap!!
            var diff = 0
            for (y in 0 until now.height step 3) for (x in 0 until now.width step 3) {
                val c = now.getPixel(x, y)
                if (c != colours.getValue(v) && c != Color.TRANSPARENT) diff++
            }
            assertEquals("$v restored by the one undo (undo=${vm.uiState.value.undoCount})", 0, diff)
        }
        assertEquals(box, vm.uiState.value.selection)
        assertEquals(0, vm.uiState.value.undoCount)
        assertNotNull(Tool.SELECT)
    }

    @Test
    fun `a flat layer's wand and move are unchanged`() {
        val layer = Layer(id = "f", name = "F", bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
        vm.dispatchForTest(EditorIntent.ActivateLayer("f"))
        vm.wandMask = { b, _, _, _ -> BooleanArray(b.width * b.height) { i -> (i % b.width) in 20 until 40 && (i / b.width) in 20 until 40 } }
        vm.onAutoSelect(Offset(850f, 600f), canvas)
        val sel = vm.uiState.value.selection
        assertNotNull(sel)
        assertEquals(canvas, sel!!.canvasSize)
        assertTrue(sel.rings.all { it.additive })
    }
}
