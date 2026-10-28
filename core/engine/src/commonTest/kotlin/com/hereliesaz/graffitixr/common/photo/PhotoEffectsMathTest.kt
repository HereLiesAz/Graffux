package com.hereliesaz.graffitixr.common.photo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Effects tab's Outline (Canny) and Sketch formulas against values worked out by hand: the
 * Sobel kernel on ramps whose slope is known, a hand-built edge plane for the thinning and
 * hysteresis, and the colour dodge in a calculator. The GPU shader follows these step for step
 * (core/wgpu-engine/tests/adjust.rs and PhotoAdjustGpuParityTest compare the two).
 */
class PhotoEffectsMathTest {

    private fun near(expected: Float, actual: Float, tol: Float = 1e-5f) =
        assertTrue(kotlin.math.abs(expected - actual) <= tol, "expected $expected, was $actual")

    private fun plane(w: Int, h: Int, f: (Int, Int) -> Float) = FloatArray(w * h) { f(it % w, it / w) }

    @Test
    fun `the Sobel code on ramps of known slope`() {
        // Slope 0.1 per pixel along x: gx = (1 + 2 + 1) * 0.2 = 0.8; scaled by (2r + 1) / 8 = 3/8 at
        // r = 1 is 0.3, direction 0 (horizontal).
        near(0.3f, PhotoAdjustMath.edgeCode(plane(7, 7) { x, _ -> x * 0.1f }, 7, 7, 1, 3, 3))
        // The same down y: direction 2.
        near(2.3f, PhotoAdjustMath.edgeCode(plane(7, 7) { _, y -> y * 0.1f }, 7, 7, 1, 3, 3))
        // Along x + y at 0.05: gx = gy = 0.4, |g| = 0.565685, x 3/8 = 0.212132, direction 1.
        near(1.212132f, PhotoAdjustMath.edgeCode(plane(7, 7) { x, y -> (x + y) * 0.05f }, 7, 7, 1, 3, 3))
        // Along x − y: the gradient points down-left, direction 3.
        near(3.212132f, PhotoAdjustMath.edgeCode(plane(7, 7) { x, y -> (x - y) * 0.05f }, 7, 7, 1, 3, 3))
        // Flat: no gradient.
        near(0f, PhotoAdjustMath.edgeCode(plane(7, 7) { _, _ -> 0.4f }, 7, 7, 1, 3, 3))
        // A full black-to-white step caps just below 1, keeping the direction in the integer part.
        near(0.999f, PhotoAdjustMath.edgeCode(plane(9, 3) { x, _ -> if (x < 5) 0f else 1f }, 9, 3, 1, 4, 1))
        // Radius scales the response: slope 0.1 at r = 3 reads 0.8 * 7 / 8 = 0.7.
        near(0.7f, PhotoAdjustMath.edgeCode(plane(7, 7) { x, _ -> x * 0.1f }, 7, 7, 3, 3, 3))
    }

    @Test
    fun `Canny thins a step to its two sides and drops weak lone edges`() {
        val w = 10
        val h = 4
        val step = plane(w, h) { x, _ -> if (x < 5) 0f else 1f }
        val lines = PhotoAdjustMath.cannyOutline(PhotoAdjustMath.edgePlane(step, w, h, 1), w, h, 0.5f)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(if (x == 4 || x == 5) 1f else 0f, lines[y * w + x], "($x, $y)")
        }
        // Nothing reaches a threshold above the cap.
        val none = PhotoAdjustMath.cannyOutline(PhotoAdjustMath.edgePlane(step, w, h, 1), w, h, 1f)
        assertTrue(none.all { it == 0f })
    }

    @Test
    fun `hysteresis keeps a weak edge only beside a strong one`() {
        // 3 x 3, every gradient vertical (direction 2: compared with the pixels above and below,
        // which are 0). Middle row: weak 0.3, strong 0.6, below-low 0.1; high 0.5, low 0.25.
        val edges = floatArrayOf(2f, 2f, 2f, 2.3f, 2.6f, 2.1f, 2f, 2f, 2f)
        val lines = PhotoAdjustMath.cannyOutline(edges, 3, 3, 0.5f)
        assertEquals(listOf(0f, 0f, 0f, 1f, 1f, 0f, 0f, 0f, 0f), lines.toList())
        // Both weak: nothing strong beside them, so neither is a line.
        edges[4] = 2.3f
        assertTrue(PhotoAdjustMath.cannyOutline(edges, 3, 3, 0.5f).all { it == 0f })
        // Non-maximum suppression: a strong pixel with a stronger one along its gradient is thinned.
        val thin = floatArrayOf(2f, 2.9f, 2f, 2f, 2.6f, 2f, 2f, 2f, 2f)
        assertEquals(0f, PhotoAdjustMath.cannyOutline(thin, 3, 3, 0.5f)[4])
    }

    @Test
    fun `the sketch is a colour dodge of the luma by its blur`() {
        // Flat: luma equals its blur, white paper.
        near(1f, PhotoAdjustMath.sketch(0.4f, 0.4f, 0.4f, 0.3f, 0f))
        // 0.3 / 0.6 = 0.5.
        near(0.5f, PhotoAdjustMath.sketch(0.3f, 0.6f, 0.2f, 0f, 0f))
        // Darkness 0.25: power 1 + 4 * 0.25 = 2, 0.25.
        near(0.25f, PhotoAdjustMath.sketch(0.3f, 0.6f, 0.2f, 0f, 0.25f))
        // Softness 0.5 blends the blurs: 0.6 + (0.2 − 0.6) / 2 = 0.4, 0.3 / 0.4 = 0.75.
        near(0.75f, PhotoAdjustMath.sketch(0.3f, 0.6f, 0.2f, 0.5f, 0f))
        // Brighter than its surroundings: clipped at white.
        near(1f, PhotoAdjustMath.sketch(0.9f, 0.5f, 0.5f, 0f, 0f))
    }

    @Test
    fun `the sliders pack into the effects block`() {
        val off = PhotoAdjustParams.pack(PhotoAdjustment(PhotoTool.EFFECTS))
        assertEquals(64, off.size)
        assertEquals(0f, off[PhotoAdjustParams.EFFECTS_ON])
        val on = PhotoAdjustParams.pack(
            PhotoAdjustment(PhotoTool.EFFECTS)
                .with(PhotoSliders.OUTLINE, 80f).with(PhotoSliders.OUTLINE_PAPER, 50f)
                .with(PhotoSliders.SKETCH, 40f).with(PhotoSliders.SKETCH_DARKNESS, 10f),
        )
        assertEquals(1f, on[PhotoAdjustParams.EFFECTS_ON])
        near(0.8f, on[56])
        near(0.2f, on[57]) // threshold default 20
        near(0.5f, on[58])
        near(0.4f, on[59])
        near(0.3f, on[60]) // softness default 30
        near(0.1f, on[61])
        assertTrue(PhotoSliders.of(PhotoTool.EFFECTS).containsAll(listOf(PhotoSliders.OUTLINE, PhotoSliders.SKETCH)))
    }

    @Test
    fun `an outline on white paper leaves only the lines dark`() {
        // A 40 x 10 grey photo, black on the left half, light on the right.
        val w = 40
        val h = 10
        val src = ByteArray(w * h * 4) { i ->
            val px = i / 4
            when {
                i % 4 == 3 -> 255.toByte()
                px % w < 20 -> 20
                else -> 220.toByte()
            }
        }
        val adj = PhotoAdjustment(PhotoTool.EFFECTS)
            .with(PhotoSliders.OUTLINE, 100f).with(PhotoSliders.OUTLINE_PAPER, 100f)
        val out = ByteArray(src.size)
        PhotoAdjustMath.render(src, w, h, PhotoAdjustParams.pack(adj), null, null, out)
        val row = (0 until w).map { out[(5 * w + it) * 4].toInt() and 0xFF }
        // White away from the step, black on it.
        assertEquals(255, row[2])
        assertEquals(255, row[37])
        assertTrue(row.subList(17, 23).any { it == 0 }, "no line at the step: $row")
        assertTrue(row.count { it == 0 } <= 4, "line too thick: $row")
    }
}
