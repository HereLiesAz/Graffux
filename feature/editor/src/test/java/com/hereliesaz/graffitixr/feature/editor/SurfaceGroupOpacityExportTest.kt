@file:Suppress("MagicNumber", "MaxLineLength")

package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.SceneCamera
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.feature.editor.export.ExportManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * A layer group on a surface composites like the same group on the flat canvas: its layers merge
 * first, then the merged image takes the group's opacity and blend (spec/3d-space.md, "Group
 * surfaces"). The surface here faces the camera at the default view, so it projects onto the canvas
 * one to one and the flat canvas is the reference.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SurfaceGroupOpacityExportTest {
    private val w = 200
    private val h = 200

    /** The 8-bit tolerance of the existing cache parity tests (StackLayerCacheTest). */
    private val tolerance = 3

    private fun band(color: Int, from: Int, to: Int) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            for (x in from until to) for (y in 0 until h) setPixel(x, y, color)
        }

    /** Facing the camera: along x, swept down y, so the surface is the picture plane. */
    private fun facing(bend: Boolean = false) = SurfaceProfile(
        if (bend) listOf(0f, 0f, 0f, w / 2f, 0f, 0f, w.toFloat(), 0f, 100f) else listOf(0f, 0f, 0f, w.toFloat(), 0f, 0f),
        sweepX = 0f, sweepY = 1f, sweepZ = 0f,
    )

    private fun doc(opacity: Float, blend: BlendMode = BlendMode.SrcOver, surface: Boolean, bend: Boolean = false): List<Layer> = listOf(
        Layer(
            "g", "G", type = LayerType.GROUP, opacity = opacity, blendMode = blend,
            surface = if (surface) facing(bend) else null,
        ),
        Layer("a", "A", parentId = "g", bitmap = band(Color.RED, 0, 120)),
        Layer("b", "B", parentId = "g", bitmap = band(Color.BLUE, 80, 200)),
    )

    private fun render(layers: List<Layer>, camera: SceneCamera?, background: Int = Color.TRANSPARENT): Bitmap {
        val export = ExportManager()
        export.depthRenderer = { _, _, _ -> null } // no GL under Robolectric: the export's fallback
        return export.compositeLayers(layers, w, h, backgroundColor = background, camera = camera)
    }

    private fun worst(a: Bitmap, b: Bitmap, x: Int, y: Int): Int {
        val p = a.getPixel(x, y)
        val q = b.getPixel(x, y)
        return maxOf(
            abs(Color.alpha(p) - Color.alpha(q)), abs(Color.red(p) - Color.red(q)),
            abs(Color.green(p) - Color.green(q)), abs(Color.blue(p) - Color.blue(q)),
        )
    }

    private fun assertSameAsFlat(opacity: Float, blend: BlendMode = BlendMode.SrcOver, background: Int = Color.TRANSPARENT) {
        val flat = render(doc(opacity, blend, surface = false), camera = null, background = background)
        val surf = render(doc(opacity, blend, surface = true), camera = SceneCamera(), background = background)
        // The overlap (both layers), and each layer alone, well inside the strips' edges.
        for ((x, y) in listOf(100 to 100, 100 to 40, 40 to 100, 170 to 160)) {
            val d = worst(flat, surf, x, y)
            assertTrue("pixel ($x,$y) differs from the flat canvas by $d: flat=${Integer.toHexString(flat.getPixel(x, y))} surface=${Integer.toHexString(surf.getPixel(x, y))}", d <= tolerance)
        }
    }

    @Test
    fun `a half-opaque group on a surface renders its overlap as the flat canvas does`() = assertSameAsFlat(0.5f)

    @Test
    fun `the same over a background`() = assertSameAsFlat(0.5f, background = Color.WHITE)

    @Test
    fun `a three-quarter group matches too`() = assertSameAsFlat(0.75f, background = Color.WHITE)

    @Test
    fun `a full-opacity group is the flat canvas, as before`() = assertSameAsFlat(1f, background = Color.WHITE)

    @Test
    fun `a group blend mode is honoured on a flat surface`() = assertSameAsFlat(1f, BlendMode.Multiply, Color.rgb(200, 180, 120))

    @Test
    fun `a translucent group blend mode is honoured on a flat surface`() = assertSameAsFlat(0.6f, BlendMode.Multiply, Color.rgb(200, 180, 120))

    @Test
    fun `a bent surface composites its translucent group as a unit`() {
        // The overlap at x = 90 lies on the part of the surface still facing the camera, so it must
        // be the merged blue at the group's opacity, as on the flat canvas.
        val surf = render(doc(0.5f, surface = true, bend = true), camera = SceneCamera(), background = Color.WHITE)
        val flat = render(doc(0.5f, surface = false), camera = null, background = Color.WHITE)
        val d = worst(flat, surf, 90, 100)
        assertTrue("overlap differs from the flat canvas by $d", d <= tolerance)
    }
}
