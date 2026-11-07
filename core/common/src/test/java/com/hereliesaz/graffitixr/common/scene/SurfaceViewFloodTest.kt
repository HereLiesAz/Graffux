@file:Suppress("MagicNumber")

package com.hereliesaz.graffitixr.common.scene

import org.junit.Assert.assertEquals
import org.junit.Test

/** The walk of a flood across creases tracks flooded components, not whole faces (spec/3d-space.md, "Fill, Clone, lasso"). */
class SurfaceViewFloodTest {
    private fun outline(sweep: FloatArray, place: (Float, Float) -> FloatArray) = SurfaceProfile(
        listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f).flatMap { place(it.first, it.second).toList() },
        sweep[0], sweep[1], sweep[2],
    )

    private val hull = SurfaceHull.of(
        outline(floatArrayOf(0f, 0f, 1f)) { x, y -> floatArrayOf(x, y, 0f) }.copy(
            side = outline(floatArrayOf(1f, 0f, 0f)) { z, y -> floatArrayOf(0f, y, z) },
        ),
    )!!

    private fun at(view: SurfaceView, x: Float, y: Float) = PaintTarget(view, x, y, 0)
    private fun link(from: PaintTarget, to: PaintTarget) = CreaseLink(floatArrayOf(0f, 0f, 0f), from, to)

    /** A flood that spreads over the whole of the face it seeds from when [wide], else within [radius] of its seed. */
    private fun oracle(radius: Float) = object : SurfaceViewFlood.Oracle {
        override fun flood(seed: PaintTarget): ((Float, Float) -> Boolean)? = { x, y ->
            kotlin.math.abs(x - seed.x) <= radius && kotlin.math.abs(y - seed.y) <= radius
        }

        override fun accept(target: PaintTarget) = target
    }

    @Test
    fun `a face with two separate components is flooded once for each the walk reaches`() {
        val tap = at(SurfaceView.FRONT, 50f, 50f)
        val links = listOf(
            link(at(SurfaceView.FRONT, 60f, 60f), at(SurfaceView.SIDE, 100f, 100f)),
            link(at(SurfaceView.FRONT, 40f, 40f), at(SurfaceView.SIDE, 900f, 900f)),
        )
        val seeds = SurfaceViewFlood.plan(hull, tap, oracle(radius = 30f), links)
        assertEquals(listOf(tap.x to tap.y, 100f to 100f, 900f to 900f), seeds.map { it.x to it.y })
    }

    @Test
    fun `a link back across the seam of a closed outline floods the far end of the same face`() {
        val tap = at(SurfaceView.FRONT, 10f, 10f)
        // The unrolled sheet's two ends are one (view, region); only the second end is a different component.
        val links = listOf(link(at(SurfaceView.FRONT, 10f, 10f), at(SurfaceView.FRONT, 900f, 10f)))
        val seeds = SurfaceViewFlood.plan(hull, tap, oracle(radius = 100f), links)
        assertEquals(listOf(10f to 10f, 900f to 10f), seeds.map { it.x to it.y })
    }

    @Test
    fun `two links into the same component plan it once`() {
        val tap = at(SurfaceView.FRONT, 50f, 50f)
        val links = listOf(
            link(at(SurfaceView.FRONT, 60f, 60f), at(SurfaceView.SIDE, 100f, 100f)),
            link(at(SurfaceView.FRONT, 40f, 40f), at(SurfaceView.SIDE, 110f, 110f)),
        )
        assertEquals(2, SurfaceViewFlood.plan(hull, tap, oracle(radius = 30f), links).size)
    }
}
