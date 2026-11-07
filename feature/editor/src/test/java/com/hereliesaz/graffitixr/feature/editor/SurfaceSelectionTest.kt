@file:Suppress("MaxLineLength", "MagicNumber", "NestedBlockDepth")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
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
    private var beforeFootprintPass: (() -> Unit)? = null
    /** Runs once, from inside the flood plan (the wand stub below), i.e. while a fill is still being planned. */
    private var beforeFillPlan: (() -> Unit)? = null

    /** Stands in for the background dispatcher: runs [beforeFootprintPass] once, as the pass is handed off. */
    private val background = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (Throwable().stackTrace.any { it.methodName.startsWith("refreshFootprint") }) {
                beforeFootprintPass?.also { beforeFootprintPass = null }?.invoke()
            }
            block.run()
        }
    }
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
        vm = EditorViewModelFixture.build(dispatcher, background = background)
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
        vm.wandMask = { b, _, _, _, _ -> BooleanArray(b.width * b.height) { true } }
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
        vm.wandMask = { b, x, y, _, _ ->
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
        vm.wandMask = { b, _, _, _, _ -> BooleanArray(b.width * b.height) { i -> (i % b.width) in 20 until 40 && (i / b.width) in 20 until 40 } }
        vm.onAutoSelect(Offset(850f, 600f), canvas)
        val sel = vm.uiState.value.selection
        assertNotNull(sel)
        assertEquals(canvas, sel!!.canvasSize)
        assertTrue(sel.rings.all { it.additive })
    }

    private fun wandOn(view: SurfaceView? = null): Pair<SurfaceView, Selection> {
        load(emptyMap())
        val ts = touches().filter { it.second.region == 0 }
        vm.wandMask = { b, _, _, _, _ -> BooleanArray(b.width * b.height) { true } }
        val tap = ts.first { view == null || it.second.view == view }.second
        vm.onViewAutoSelect("p", tap, canvas)
        return tap.view to vm.uiState.value.selection!!
    }

    private fun touchesAt(c: SceneCamera) = buildList {
        var y = 0f
        while (y < h) {
            var x = 0f
            while (x < w) {
                SurfaceMath.worldToViewFlat(x, y, owner, c, w, h, hull)?.let { add(Offset(x, y) to it) }
                x += 20f
            }
            y += 20f
        }
    }

    @Test
    fun `an orbit keeps the wand's image regions and moves its footprint with the surface`() {
        val (view, first) = wandOn()
        assertNotNull(first.surface)
        val other = SceneCamera(yaw = -0.6f, pitch = 0.3f)
        vm.setSceneCameraForTest(other)
        val now = vm.uiState.value.selection!!
        assertEquals(first.surface, now.surface)
        assertTrue("footprint recomputed", now.rings != first.rings)
        val own = touchesAt(other).filter { it.second.view == view && it.second.region == 0 }
        assertTrue(own.isNotEmpty())
        assertTrue("selected where the face now shows", own.count { now.holds(it.first) } > own.size / 2)
        // Back again: the footprint is the one it was.
        vm.setSceneCameraForTest(camera)
        assertEquals(first.rings, vm.uiState.value.selection!!.rings)
    }

    @Test
    fun `a selection flag toggled during a footprint pass survives it and the rings are the new ones`() {
        val (_, first) = wandOn()
        assertTrue(!first.inverted && first.featherPx == 0f)
        // The user flips invert and feather just as the background pass starts, after it snapshotted the selection.
        beforeFootprintPass = {
            vm.dispatchForTest(EditorIntent.InvertSelection)
            vm.dispatchForTest(EditorIntent.SetSelectionFeather(7f))
        }
        vm.setSceneCameraForTest(SceneCamera(yaw = -0.6f, pitch = 0.3f))
        val now = vm.uiState.value.selection!!
        assertTrue("invert survives", now.inverted)
        assertEquals("feather survives", 7f, now.featherPx, 0f)
        assertTrue("rings are the new footprint", now.rings != first.rings)
    }

    @Test
    fun `a lasso added onto a wand joins the image regions and undoing a move restores them`() {
        val (_, first) = wandOn()
        val ts = touches().filter { it.second.region == 0 }
        val at = ts.first { it.second.view != first.surface!!.views.first().view }.first
        vm.dispatchForTest(EditorIntent.SetSelectionOp(com.hereliesaz.graffitixr.common.model.SelectionOp.ADD))
        vm.onSelectionEnd(listOf(at - Offset(20f, 20f), Offset(at.x + 20f, at.y - 20f), at + Offset(20f, 20f), Offset(at.x - 20f, at.y + 20f)), canvas)
        val both = vm.uiState.value.selection!!
        assertNotNull(both.surface)
        assertTrue(both.surface!!.views.size > first.surface!!.views.size)
        assertTrue(both.holds(at))
        vm.onSurfaceSelectionMove("p", Offset(40f, 0f))
        val moved = vm.uiState.value.selection!!
        assertNotNull("a moved surface selection still follows the surface", moved.surface)
        vm.onUndoClicked()
        assertEquals(both.surface, vm.uiState.value.selection!!.surface)
    }

    // ── A plain lasso on a multi-view surface follows the surface, like the wand ──────────────────────

    private fun box(c: Offset, r: Float) = listOf(Offset(c.x - r, c.y - r), Offset(c.x + r, c.y - r), Offset(c.x + r, c.y + r), Offset(c.x - r, c.y + r))

    private fun middleOf(view: SurfaceView): Offset {
        val own = touches().filter { it.second.view == view && it.second.region == 0 && interior(it.first, it.second) }.map { it.first }
        val m = Offset(own.sumOf { it.x.toDouble() }.toFloat() / own.size, own.sumOf { it.y.toDouble() }.toFloat() / own.size)
        return own.minBy { (it - m).getDistance() }
    }

    private fun lassoOnFace(view: SurfaceView? = null, r: Float = 60f): Pair<Offset, Selection> {
        load(colours)
        val v = view ?: touches().first { it.second.region == 0 }.second.view
        val at = middleOf(v)
        vm.onSelectionEnd(box(at, r), canvas)
        return at to vm.uiState.value.selection!!
    }

    @Test
    fun `a lasso drawn on the surface is stored per view image and follows an orbit`() {
        val (at, first) = lassoOnFace()
        assertNotNull("the lasso is a surface selection", first.surface)
        assertEquals("p", first.surface!!.surfaceId)
        assertTrue(first.holds(at))
        val other = SceneCamera(yaw = -0.6f, pitch = 0.3f)
        vm.setSceneCameraForTest(other)
        val now = vm.uiState.value.selection!!
        assertEquals("the image regions are what was drawn", first.surface, now.surface)
        assertTrue("footprint recomputed for the new camera", now.rings != first.rings)
        // Same camera again: nothing to recompute, the selection is the very same value.
        vm.setSceneCameraForTest(other)
        assertTrue(now === vm.uiState.value.selection)
        // Back: the footprint is the one drawn.
        vm.setSceneCameraForTest(camera)
        assertEquals(first.rings, vm.uiState.value.selection!!.rings)
    }

    @Test
    fun `a lasso clips painting per view image, follows the camera and is a miss on views it never reached`() {
        val (_, first) = lassoOnFace()
        val reached = first.surface!!.views.map { it.view }.toSet()
        assertTrue(reached.isNotEmpty())
        val seen = touches().filter { it.second.region == 0 }.map { it.second.view }.distinct()
        for (v in seen) {
            val clip = vm.viewClip("p", v)
            if (v in reached) {
                assertTrue("$v is reached", !clip.blocked && clip.selection != null)
            } else {
                assertTrue("$v was never reached by the lasso: a miss", clip.blocked)
            }
        }
        // A small orbit: the same walls still face the camera, so the lasso still clips them, wherever they now show.
        vm.setSceneCameraForTest(SceneCamera(yaw = 0.45f, pitch = 0.3f))
        for (v in reached) {
            val clip = vm.viewClip("p", v)
            assertTrue("$v still reached after the orbit", !clip.blocked && clip.selection != null)
        }
        // Seen from behind, what the lasso covered turns away: nothing of it can be painted.
        vm.setSceneCameraForTest(SceneCamera(yaw = 0.6f + Math.PI.toFloat(), pitch = 0.3f))
        for (v in reached) assertTrue("$v turned away", vm.viewClip("p", v).blocked)
    }

    @Test
    fun `an inverted lasso selects everything outside it on every view the surface shows`() {
        val (at, first) = lassoOnFace()
        vm.onInvertSelection()
        val inverted = vm.uiState.value.selection!!
        assertTrue(inverted.inverted)
        assertEquals(first.surface, inverted.surface)
        val shown = touches().filter { it.second.region == 0 }.map { it.second.view }.distinct()
        assertTrue(shown.size >= 2)
        var outside = 0
        for (v in shown) {
            val clip = vm.viewClip("p", v)
            assertTrue("inverted is not a miss on $v", !clip.blocked && clip.selection != null)
            for ((p, f) in touches().filter { it.second.view == v && it.second.region == 0 && interior(it.first, it.second) }) {
                val under = kotlin.math.abs(p.x - at.x) < 50f && kotlin.math.abs(p.y - at.y) < 50f
                val near = kotlin.math.abs(p.x - at.x) < 72f && kotlin.math.abs(p.y - at.y) < 72f
                if (!under && near) continue
                assertEquals("$p on $v", !under, clip.selection!!.holds(Offset(f.x, f.y)))
                if (!under) outside++
            }
        }
        assertTrue(outside > 100)
    }

    @Test
    fun `lassos fold per image under New, Add and Remove, and combine with a wand`() {
        val (at, first) = lassoOnFace(r = 40f)
        val views = touches().filter { it.second.region == 0 }.map { it.second.view }.distinct()
        val otherView = views.first { it != first.surface!!.views.first().view }
        val there = middleOf(otherView)
        vm.dispatchForTest(EditorIntent.SetSelectionOp(com.hereliesaz.graffitixr.common.model.SelectionOp.ADD))
        vm.onSelectionEnd(box(there, 40f), canvas)
        val added = vm.uiState.value.selection!!
        assertNotNull(added.surface)
        assertTrue(added.holds(at) && added.holds(there))
        assertTrue(added.surface!!.views.map { it.view }.containsAll(listOf(first.surface!!.views.first().view, otherView)))
        vm.dispatchForTest(EditorIntent.SetSelectionOp(com.hereliesaz.graffitixr.common.model.SelectionOp.REMOVE))
        vm.onSelectionEnd(box(at, 15f), canvas)
        val cut = vm.uiState.value.selection!!
        assertTrue(!cut.holds(at) && cut.holds(there) && cut.holds(at + Offset(30f, 0f)))
        // A wand added on top of the lasso regions keeps them.
        vm.dispatchForTest(EditorIntent.SetSelectionOp(com.hereliesaz.graffitixr.common.model.SelectionOp.ADD))
        vm.wandMask = { b, _, _, _, _ -> BooleanArray(b.width * b.height) { true } }
        val third = views.firstOrNull { it != first.surface!!.views.first().view && it != otherView }
        val tap = touches().first { it.second.region == 0 && it.second.view == (third ?: otherView) }.second
        vm.onViewAutoSelect("p", tap, canvas)
        val mixed = vm.uiState.value.selection!!
        assertTrue(mixed.holds(there) && mixed.holds(at + Offset(30f, 0f)) && !mixed.holds(at))
        // Replacing: New is just the new lasso.
        vm.dispatchForTest(EditorIntent.SetSelectionOp(com.hereliesaz.graffitixr.common.model.SelectionOp.NEW))
        vm.onSelectionEnd(box(there, 30f), canvas)
        val fresh = vm.uiState.value.selection!!
        assertTrue(fresh.holds(there) && !fresh.holds(at + Offset(30f, 0f)))
    }

    @Test
    fun `a moved lasso keeps following and undo restores its image regions, saved lassos load back`() {
        val (_, first) = lassoOnFace(r = 50f)
        vm.onSurfaceSelectionMove("p", Offset(40f, 0f))
        val moved = vm.uiState.value.selection!!
        assertNotNull("a moved lasso still follows the surface", moved.surface)
        assertTrue(moved.rings != first.rings)
        vm.onUndoClicked()
        assertEquals(first.surface, vm.uiState.value.selection!!.surface)
        assertEquals(first.rings, vm.uiState.value.selection!!.rings)
        vm.onSaveSelection("lasso")
        vm.onClearSelection()
        vm.onLoadSelection("lasso")
        assertEquals(first, vm.uiState.value.selection)
        vm.setSceneCameraForTest(SceneCamera(yaw = -0.6f, pitch = 0.3f))
        assertEquals(first.surface, vm.uiState.value.selection!!.surface)
    }

    @Test
    fun `a lasso on a flat layer is unchanged`() {
        val layer = Layer(id = "f", name = "F", bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(layer)))
        vm.dispatchForTest(EditorIntent.ActivateLayer("f"))
        val pts = box(Offset(850f, 600f), 100f)
        vm.onSelectionEnd(pts, canvas)
        val sel = vm.uiState.value.selection!!
        assertEquals(null, sel.surface)
        assertEquals(SelectionGeometry.compose(null, SelectionGeometry.simplify(pts), canvas, com.hereliesaz.graffitixr.common.model.SelectionOp.NEW), sel)
        vm.setSceneCameraForTest(SceneCamera(yaw = -0.6f, pitch = 0.3f))
        assertTrue("a flat selection is not re-derived", sel === vm.uiState.value.selection)
    }

    // ── Fill across creases (Codex review of the flood fill) ─────────────────────────────────────────

    /** Only [view]'s image exists; every other view is blank-by-absence, as on a fresh multi-view surface. */
    private fun loadOnly(view: SurfaceView) {
        val group = Layer("g", "G", type = LayerType.GROUP, surface = cube)
        val leaf = Layer("p", "P", parentId = "g")
        val img = image(view, null)
        vm.dispatchForTest(EditorIntent.SetLayers(listOf(group, leaf, img)))
        vm.dispatchForTest(EditorIntent.ActivateLayer("p"))
        vm.putLayerBaseForTest(img.id, img.bitmap!!.copy(Bitmap.Config.ARGB_8888, true))
    }

    private fun fillStrokes(view: SurfaceView) =
        vm.uiState.value.layers.filter { it.surfaceViewOf == "p" && it.surfaceView == view }
            .flatMap { vm.recordedStrokesForTest(it.id) }.filter { it.tool == Tool.FILL }

    @Test
    fun `a flood that reaches a view with no image yet fills it in the same gesture`() {
        loadOnly(SurfaceView.FRONT)
        val ts = touches().filter { it.second.region == 0 }
        val views = ts.map { it.second.view }.distinct()
        assertTrue("views $views", views.size >= 2)
        // Start from a shown view that has an image; the rest have none.
        val start = SurfaceView.FRONT.takeIf { it in views } ?: views[0]
        if (start != SurfaceView.FRONT) loadOnly(start)
        vm.wandMask = { b, _, _, _, _ -> BooleanArray(b.width * b.height) { true } }
        vm.setActiveTool(Tool.FILL)
        vm.onViewFillTap("p", ts.first { it.second.view == start }.second, canvas)
        assertTrue("the tapped view is filled", fillStrokes(start).isNotEmpty())
        assertTrue(
            "a neighbouring view created by the flood is filled too",
            (views - start).any { fillStrokes(it).isNotEmpty() },
        )
    }

    @Test
    fun `a fill keeps the colour and tool it was tapped with while the flood plans in the background`() {
        load(emptyMap())
        val ts = touches().filter { it.second.region == 0 }
        vm.wandMask = { b, _, _, _, _ ->
            beforeFillPlan?.also { beforeFillPlan = null }?.invoke()
            BooleanArray(b.width * b.height) { true }
        }
        vm.setActiveTool(Tool.FILL)
        vm.setActiveColor(androidx.compose.ui.graphics.Color.Red)
        // The user switches to a brush with another colour just after tapping, before the plan lands.
        var switched = false
        beforeFillPlan = {
            switched = true
            vm.setActiveColor(androidx.compose.ui.graphics.Color.Blue)
            vm.setActiveTool(Tool.BRUSH)
        }
        val tap = ts.first().second
        vm.onViewFillTap("p", tap, canvas)
        assertTrue("the switch happened while the plan was pending", switched)
        val fills = fillStrokes(tap.view)
        assertTrue("the tapped fill is not lost to the tool switch", fills.isNotEmpty())
        assertEquals(androidx.compose.ui.graphics.Color.Red.toArgb(), fills.first().brushColor)
    }

    @Test
    fun `a surface selection whose faces have turned away still blocks flat layers instead of selecting nothing`() {
        lassoOnFace()
        vm.setSceneCameraForTest(SceneCamera(yaw = 0.6f + Math.PI.toFloat(), pitch = 0.3f))
        val sel = vm.uiState.value.selection!!
        assertTrue("its footprint is empty from behind", sel.rings.none { it.additive && it.isUsable })
        assertTrue("but the stored regions are kept", sel.surface!!.isUsable)
        val path = SelectionMask.bitmapPath(sel, 64, 64)
        assertNotNull("a flat layer is confined, not left unclipped", path)
        assertTrue("to nothing", path!!.isEmpty)
    }

    @Test
    fun `an inverted surface selection with no footprint still selects everything on a flat layer`() {
        lassoOnFace()
        vm.onInvertSelection()
        vm.setSceneCameraForTest(SceneCamera(yaw = 0.6f + Math.PI.toFloat(), pitch = 0.3f))
        val sel = vm.uiState.value.selection!!
        assertTrue(sel.inverted)
        assertEquals(null, SelectionMask.bitmapPath(sel.copy(rings = emptyList()), 64, 64))
    }

    @Test
    fun `loading a saved surface selection re-derives its footprint for the camera it is loaded at`() {
        lassoOnFace()
        vm.onSaveSelection("s")
        // Orbit with the selection still on: it follows the camera, so loading the saved one changes neither its
        // image regions nor the footprint key the live footprint pass watches.
        vm.setSceneCameraForTest(SceneCamera(yaw = -0.6f, pitch = 0.3f))
        val followed = vm.uiState.value.selection!!
        vm.onLoadSelection("s")
        val loaded = vm.uiState.value.selection!!
        assertEquals(followed.surface, loaded.surface)
        assertEquals("the ants are where the faces show now, not where they showed when it was saved", followed.rings, loaded.rings)
    }
}
