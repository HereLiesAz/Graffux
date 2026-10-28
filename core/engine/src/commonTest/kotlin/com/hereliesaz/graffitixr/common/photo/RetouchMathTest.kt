package com.hereliesaz.graffitixr.common.photo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetouchMathTest {

    private val w = 80
    private val h = 60

    /** An opaque image filled by [rgb](x, y). */
    private fun image(rgb: (Int, Int) -> IntArray): ByteArray = ByteArray(w * h * 4).also { b ->
        for (y in 0 until h) for (x in 0 until w) {
            val c = rgb(x, y)
            val i = (y * w + x) * 4
            for (k in 0..2) b[i + k] = c[k].toByte()
            b[i + 3] = 255.toByte()
        }
    }

    private fun at(b: ByteArray, x: Int, y: Int, k: Int) = b[(y * w + x) * 4 + k].toInt() and 0xFF

    /** [out] (the heal layer) drawn over [src]. */
    private fun over(src: ByteArray, out: ByteArray): ByteArray = ByteArray(src.size) { i ->
        val px = i / 4 * 4
        val a = (out[px + 3].toInt() and 0xFF) / 255f
        ((out[i].toInt() and 0xFF) + (src[i].toInt() and 0xFF) * (1f - a)).toInt().coerceIn(0, 255).toByte()
    }

    @Test
    fun `falloff is solid inside and smooth to the edge`() {
        assertEquals(1f, RetouchMath.falloff(0.3f, 0.4f))
        assertEquals(0f, RetouchMath.falloff(1f, 0.4f))
        // Halfway through the feather band (0.6..1): smoothstep(0.5) = 0.5.
        assertEquals(0.5f, RetouchMath.falloff(0.8f, 0.4f), 1e-5f)
    }

    @Test
    fun `heal removes a dark spot from a gradient, matching the brightness round it`() {
        val src = image { x, y ->
            val g = 60 + x * 2
            if ((x - 40) * (x - 40) + (y - 30) * (y - 30) <= 9) intArrayOf(10, 10, 10) else intArrayOf(g, g, g)
        }
        val (sx, sy) = RetouchMath.autoSource(src, w, h, 40f, 30f, 6f)
        // Same column band preferred: the gradient runs along x, so a vertical neighbour matches best.
        assertTrue(abs(sx - 40f) < 6f, "source $sx,$sy")
        val out = ByteArray(src.size)
        RetouchMath.heal(src, w, h, HealSpot(40f, 30f, 6f, sx, sy, feather = 0.3f), contentAware = true, out = out)
        val healed = over(src, out)
        // The spot's centre is back to the gradient's 140, not the dark 10.
        assertTrue(abs(at(healed, 40, 30, 0) - 140) <= 6, "healed ${at(healed, 40, 30, 0)}")
        // Outside the spot nothing was written.
        assertEquals(0, at(out, 10, 10, 3))
    }

    @Test
    fun `content-aware shifts a patch from a lighter area to the target's level, clone does not`() {
        // Left half 80, right half 160; heal a spot on the left from the right.
        val src = image { x, _ -> if (x < 40) intArrayOf(80, 80, 80) else intArrayOf(160, 160, 160) }
        val spot = HealSpot(20f, 30f, 5f, 60f, 30f, feather = 0f)
        val aware = ByteArray(src.size)
        RetouchMath.heal(src, w, h, spot, contentAware = true, out = aware)
        assertEquals(80, at(over(src, aware), 20, 30, 0), "content-aware")
        val clone = ByteArray(src.size)
        RetouchMath.heal(src, w, h, spot, contentAware = false, out = clone)
        assertEquals(160, at(over(src, clone), 20, 30, 0), "clone")
    }

    @Test
    fun `red-eye takes red down only where red leads`() {
        val src = image { x, _ -> if (x < 40) intArrayOf(220, 40, 50) else intArrayOf(200, 180, 160) }
        val out = ByteArray(src.size)
        RetouchMath.redEye(src, w, h, RedEyeSpot(30f, 30f, 20f), out)
        val fixed = over(src, out)
        assertEquals(45, at(fixed, 30, 30, 0))
        // A warm skin tone at the circle's edge is left alone.
        assertEquals(0, at(out, 45, 30, 3))
    }

    @Test
    fun `mask dabs build up with flow and erase takes away`() {
        val mw = 20
        val mask = ByteArray(mw * mw)
        MaskPaint.dab(mask, mw, mw, 10f, 10f, 5f, 0f, 0.5f, erase = false)
        assertEquals(128, mask[10 * mw + 10].toInt() and 0xFF)
        MaskPaint.dab(mask, mw, mw, 10f, 10f, 5f, 0f, 0.5f, erase = false)
        // The stored 128 is 0.50196; + (1 - 0.50196) * 0.5 = 0.75098, x 255 = 191.5, rounds to 192.
        assertEquals(192, mask[10 * mw + 10].toInt() and 0xFF)
        MaskPaint.dab(mask, mw, mw, 10f, 10f, 5f, 0f, 1f, erase = true)
        assertEquals(0, mask[10 * mw + 10].toInt() and 0xFF)
        assertEquals(0, mask[0].toInt() and 0xFF)
    }
}
