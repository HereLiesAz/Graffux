package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.PlanePose
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SurfaceMath
import com.hereliesaz.graffitixr.feature.editor.export.ExportManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.hereliesaz.graffitixr.common.scene.SceneMath
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The no-GLES export fallback draws paint on a group surface on the surface, not on a flat plane. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SurfaceExportTest {
    private val w = 400
    private val h = 400

    private fun line(from: Pair<Float, Float>, to: Pair<Float, Float>, n: Int = 20) = (0..n).map {
        val f = it / n.toFloat()
        (from.first + (to.first - from.first) * f) to (from.second + (to.second - from.second) * f)
    }

    @Test
    fun `the fallback follows the surface profile`() {
        // An L drawn at the default view: a wall down the left, then a floor along the bottom, both
        // running away from the viewer.
        val ell = line(100f to 100f, 100f to 300f) + line(100f to 300f, 300f to 300f).drop(1)
        val profile = SurfaceMath.capture(ell, SceneCamera(), w.toFloat(), h.toFloat(), listOf(PlanePose()))!!
        val paint = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val layers = listOf(
            Layer("g", "G", type = LayerType.GROUP, surface = profile),
            Layer("c", "C", parentId = "g", bitmap = paint),
        )
        val export = ExportManager()
        export.depthRenderer = { _, _, _ -> null } // no GL: the fallback
        val out = export.compositeLayers(layers, w, h, camera = SceneCamera())
        // Beside the wall, just behind the picture plane: painted.
        assertEquals(Color.BLUE, out.getPixel(104, 200))
        // Far outside the L and its recession: nothing. A flat plane would cover the whole canvas.
        assertEquals(0, Color.alpha(out.getPixel(380, 20)))
    }

    @Test
    fun `a Multiply layer on a flat surface draws on the surface, on canvas and in export`() {
        // A straight wall down the left at the default view, running away from the viewer. The
        // depth pass cannot blend, so the Multiply layer draws in the stack through a homography.
        val wall = line(100f to 100f, 100f to 300f)
        val profile = SurfaceMath.capture(wall, SceneCamera(), w.toFloat(), h.toFloat(), listOf(PlanePose()))!!
        assertTrue(profile.isFlat)
        val paint = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val layers = listOf(
            Layer("g", "G", type = LayerType.GROUP, surface = profile),
            Layer(
                "c", "C", parentId = "g", bitmap = paint,
                blendMode = androidx.compose.ui.graphics.BlendMode.Multiply,
            ),
        )
        assertTrue("c" !in com.hereliesaz.graffitixr.common.scene.DepthComposite.plan(layers, SceneCamera()).surfaceIds)

        // Canvas: the layer's placement maps its paint where the surface shows it.
        val owner = SurfaceMath.ownerOf(layers, "c")!!
        val ctx = SceneContext(
            SceneCamera(), SceneMath.frame(SceneCamera(), w.toFloat(), h.toFloat()),
            com.hereliesaz.graffitixr.common.scene.SceneRenderPath.sceneLayerIds(layers, SceneCamera()),
            flatSurfaceModels = SurfaceMath.flatSurfaceModels(layers, w.toFloat(), h.toFloat()),
        )
        val placement = ctx.placementFor(layers[1])!!
        listOf(10f to 5f, 120f to 40f, 200f to 150f).forEach { (x, y) ->
            val expected = SurfaceMath.flatToWorld(x, y, owner, SceneCamera(), w.toFloat(), h.toFloat())!!
            val shown = SceneMath.applyHomography(placement.homography, x, y)!!
            assertEquals(expected.first, shown.first, 0.5f)
            assertEquals(expected.second, shown.second, 0.5f)
        }

        // Export: the same.
        val export = ExportManager()
        export.depthRenderer = { _, _, _ -> null }
        val out = export.compositeLayers(layers, w, h, camera = SceneCamera())
        assertEquals(Color.BLUE, out.getPixel(104, 200))
        // On the group's picture plane it would cover the whole canvas.
        assertEquals(0, Color.alpha(out.getPixel(380, 20)))
    }
}
