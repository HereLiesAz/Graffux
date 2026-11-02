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
}
