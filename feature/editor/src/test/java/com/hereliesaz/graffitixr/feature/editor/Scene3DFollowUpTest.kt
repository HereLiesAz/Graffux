package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.model.ShapeKind
import com.hereliesaz.graffitixr.common.model.VectorShape
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SceneMath
import com.hereliesaz.graffitixr.common.scene.planeChain
import com.hereliesaz.graffitixr.feature.editor.export.ExportManager
import com.hereliesaz.graffitixr.feature.editor.threed.DepthCompositor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The 3D-space follow-ups: export, vectors, hit-testing and the lasso through tilted planes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Scene3DFollowUpTest {
    private val w = 400
    private val h = 400

    private fun solid(color: Int, size: Int = 400) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun rect(id: String, argb: Long, side: Float = 200f) =
        Layer(id, id, type = LayerType.VECTOR, shapes = listOf(VectorShape(ShapeKind.RECTANGLE, width = side, height = side, fillArgb = argb, strokeWidth = 0f)))

    @Test
    fun `a vector layer rasterizes to its own shapes, centred where the canvas draws them`() {
        val image = VectorRaster.rasterize(rect("v", 0xFFFF0000L), 1000f, 1000f)!!
        assertEquals(listOf(400f, 400f, 600f, 600f), image.rect.toList())
        assertEquals(Color.RED, image.bitmap.getPixel(image.bitmap.width / 2, image.bitmap.height / 2))
    }

    @Test
    fun `glTF scene export includes vector layers as textured quads`() {
        val state = EditorUiState(layers = listOf(rect("v", 0xFF00FF00L).copy(rotationY = 30f)), canvasSize = IntSize(w, h))
        val scene = buildSceneGltf(state, w.toFloat(), h.toFloat(), includeCamera = false)
        assertEquals(1, scene.quads.size)
        assertEquals("v", scene.quads.single().name)
        assertTrue(scene.quads.single().png.size > 8)
    }

    @Test
    fun `a flat document exports exactly as before, camera or not`() {
        val layers = listOf(Layer("a", "A", bitmap = solid(Color.BLUE)), rect("v", 0xFFFF0000L))
        val export = ExportManager()
        val flat = export.compositeLayers(layers, w, h)
        val withCamera = export.compositeLayers(layers, w, h, camera = SceneCamera())
        assertTrue(flat.sameAs(withCamera))
    }

    @Test
    fun `export shows a plane from the current camera, and uses the depth pass for members`() {
        val pushed = Layer("a", "A", bitmap = solid(Color.BLUE), positionZ = SceneMath.focalLength(h.toFloat()))
        val export = ExportManager()
        var passed: DepthCompositor.Scene? = null
        export.depthRenderer = { scene, _, _ -> passed = scene; null } // no GL here: fall back
        val out = export.compositeLayers(listOf(pushed), w, h, camera = SceneCamera())
        assertEquals(listOf("a"), passed!!.planes.map { it.id })
        // Twice as far: half size about the centre. The middle is blue, the corner empty.
        assertEquals(Color.BLUE, out.getPixel(w / 2, h / 2))
        assertEquals(0, Color.alpha(out.getPixel(10, 10)))
        // The GPU pass's image is drawn at the pass's place.
        export.depthRenderer = { _, ww, hh -> solid(Color.GREEN, ww).also { assertEquals(ww, hh) } }
        assertEquals(Color.GREEN, export.compositeLayers(listOf(pushed), w, h, camera = SceneCamera()).getPixel(10, 10))
    }

    @Test
    fun `a blend-mode layer and a clipped frame export through their 3D placement`() {
        val far = SceneMath.focalLength(h.toFloat())
        val multiply = Layer("m", "M", bitmap = solid(Color.WHITE), blendMode = BlendMode.Multiply, positionZ = far)
        val export = ExportManager()
        val out = export.compositeLayers(listOf(Layer("bg", "BG", bitmap = solid(Color.RED)), multiply), w, h, camera = SceneCamera())
        // White multiplied over red is red, inside the shrunk plane and outside it alike.
        assertEquals(Color.RED, out.getPixel(w / 2, h / 2))
        // A clipping frame pushed back clips its child to the frame's projected box, not the flat one.
        val frame = Layer("f", "F", type = LayerType.FRAME, clipsContent = true, layoutWidth = 400f, layoutHeight = 400f, positionZ = far)
        val child = Layer("c", "C", parentId = "f", bitmap = solid(Color.BLUE), blendMode = BlendMode.Screen)
        val clipped = export.compositeLayers(listOf(frame, child), w, h, camera = SceneCamera())
        assertEquals(Color.BLUE, clipped.getPixel(w / 2, h / 2))
        assertEquals(0, Color.alpha(clipped.getPixel(20, 20)))
    }

    @Test
    fun `tap-select hits the nearest plane under the finger`() {
        val back = rect("back", 0xFFFF0000L, 300f).copy(positionZ = 300f)
        val front = rect("front", 0xFF00FF00L, 300f).copy(positionZ = -100f)
        // Stack order puts the far one on top; in 3D the near one is what is seen.
        val hit = CanvasHitTest.topHit(listOf(front, back), Offset(w / 2f, h / 2f), w.toFloat(), h.toFloat(), camera = SceneCamera())
        assertEquals("front", hit)
        // A tap where only the tilted plane's projection is misses the flat footprint math.
        val tilted = rect("t", 0xFF0000FFL, 300f).copy(rotationY = 60f)
        assertNull(CanvasHitTest.topHit(listOf(tilted), Offset(w / 2f + 120f, h / 2f), w.toFloat(), h.toFloat(), camera = SceneCamera()))
        assertEquals("t", CanvasHitTest.topHit(listOf(tilted), Offset(w / 2f + 40f, h / 2f), w.toFloat(), h.toFloat(), camera = SceneCamera()))
    }

    @Test
    fun `the lasso maps through the tilted plane and its ants map back`() {
        val tilted = Layer("t", "T", rotationX = 40f, positionZ = 80f)
        val state = EditorUiState(layers = listOf(tilted), activeLayerId = "t", canvasSize = IntSize(w, h), sceneCamera = SceneCamera(yaw = 0.2f))
        val mapping = ActivePlaneMapping.of(state)
        val screen = Offset(220f, 180f)
        val flat = mapping.toFlat(screen)!!
        assertNotEquals(screen, flat)
        // Back to the world point under the finger (the screen with the 2D view undone).
        val back = mapping.flatToWorldOrNull()!!(flat)!!
        val world = mapping.world(screen)
        assertEquals(world.x, back.x, 0.05f)
        assertEquals(world.y, back.y, 0.05f)
        // A drag moves the selection by the flat amount under the finger.
        val outline = listOf(flat, flat + Offset(1f, 0f), flat + Offset(0f, 1f))
        val delta = mapping.flatDelta(Offset(10f, 0f), outline)
        assertNotEquals(Offset.Zero, delta)
        // A flat layer is the plain conversion.
        val flatState = state.copy(layers = listOf(Layer("t", "T")), sceneCamera = SceneCamera())
        assertNull(ActivePlaneMapping.of(flatState).flatToWorldOrNull())
        assertEquals(ActivePlaneMapping.of(flatState).world(screen), ActivePlaneMapping.of(flatState).toFlat(screen))
        assertTrue(planeChain(state.layers, "t").isNotEmpty())
    }
}
