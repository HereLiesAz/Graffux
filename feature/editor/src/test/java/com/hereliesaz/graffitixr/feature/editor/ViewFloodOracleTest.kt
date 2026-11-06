package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Color
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.LayerType
import com.hereliesaz.graffitixr.common.scene.SurfaceHull
import com.hereliesaz.graffitixr.common.scene.SurfaceProfile
import com.hereliesaz.graffitixr.common.scene.SurfaceView
import com.hereliesaz.graffitixr.common.scene.SurfaceViewFlood
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import io.mockk.every
import io.mockk.mockkObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A fill spreads across a crease into the neighbouring view image (spec/3d-space.md, "Fill, Clone, lasso"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewFloodOracleTest {
    private fun outline(sweep: FloatArray, place: (Float, Float) -> FloatArray) = SurfaceProfile(
        listOf(300f to 200f, 700f to 200f, 700f to 600f, 300f to 600f).flatMap { place(it.first, it.second).toList() },
        sweep[0], sweep[1], sweep[2],
    )

    private val cube = outline(floatArrayOf(0f, 0f, 1f)) { x, y -> floatArrayOf(x, y, 0f) }.copy(
        side = outline(floatArrayOf(1f, 0f, 0f)) { z, y -> floatArrayOf(300f, y, z - 300f) },
        top = outline(floatArrayOf(0f, 1f, 0f)) { x, z -> floatArrayOf(x, 200f, z - 300f) },
    )
    // Big enough that every face's paint points land inside the images, as grown layers do on a device.
    private val SIZE_W = 3000
    private val SIZE_H = 2400
    private val hull = SurfaceHull.of(cube)!!

    init {
        // The OpenCV-backed ImageProcessor loads native libraries when it first runs; none exist on a host JVM.
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
    }

    private fun image(id: String, view: SurfaceView, paint: Int?) = Layer(
        id = id, name = id, parentId = "g", isVisible = false, surfaceViewOf = "p", surfaceView = view,
        bitmap = Bitmap.createBitmap(SIZE_W, SIZE_H, Bitmap.Config.ARGB_8888).also { if (paint != null) it.eraseColor(paint) },
    )

    private val tapView = hull.meshByView().keys.first()

    /** Every view's image, blank, except those not [tapView] when [othersPaint] is set. */
    private fun layers(othersPaint: Int?) = listOf(
        Layer("g", "G", type = LayerType.GROUP, surface = cube),
        Layer("p", "P", parentId = "g"),
    ) + SurfaceView.entries.map { v -> image("p${v.name}", v, othersPaint.takeIf { v != tapView }) }

    private fun plan(othersPaint: Int?): List<com.hereliesaz.graffitixr.common.scene.PaintTarget> {
        val oracle = ViewFloodOracle(
            layers(othersPaint), "p", hull, SIZE_W, SIZE_H, 0, { b, x, y, t -> MagicWand.mask(b, x, y, t) },
        ) { EditorViewModel.ViewClip(false, null) }
        val tapFace = tapView
        val tri = hull.meshByView().getValue(tapFace)
        val c = hull.paintPointAt(tapFace, tri[3], tri[4])!!
        // A point well inside the first triangle of the tapped view's first patch.
        val mid = hull.paintPointAt(tapFace, (tri[3] + tri[8] + tri[13]) / 3f, (tri[4] + tri[9] + tri[14]) / 3f)!!
        assertTrue(c.first.isFinite())
        val tap = com.hereliesaz.graffitixr.common.scene.PaintTarget(tapFace, mid.first, mid.second, 0)
        return SurfaceViewFlood.plan(hull, tap, oracle)
    }

    @Test
    fun `blank images flood on from the tapped face into every face across the creases`() {
        val seeds = plan(null)
        assertEquals(hull.meshByView().keys, seeds.map { it.view }.toSet())
    }

    @Test
    fun `a face already painted another colour is not entered`() {
        assertEquals(1, plan(Color.RED).size)
    }
}
