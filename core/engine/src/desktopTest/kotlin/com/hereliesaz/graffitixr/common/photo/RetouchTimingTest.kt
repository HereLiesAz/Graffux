package com.hereliesaz.graffitixr.common.photo

import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Prints RETOUCH_TIMING: the full-res Heal render before the cache, and an edit with it. */
class RetouchTimingTest {

    @Test
    fun `an edited spot costs its own area, not the image`() {
        val w = 1080
        val h = 2400
        val src = ByteArray(w * h * 4) { i -> if (i % 4 == 3) -1 else ((i * 37) % 251).toByte() }
        val mask = ByteArray(w * h) { -1 }
        val spots = (0 until 12).map { HealSpot(x = 100f + it * 70f, y = 400f + it * 120f, radius = 40f, sourceX = 100f + it * 70f, sourceY = 250f + it * 120f) }
        fun median(block: () -> Unit): Double = (0 until 9).map {
            val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6
        }.sorted()[4]
        // The old path: clear the image, every spot, then a full-res mask pass.
        val full = median {
            val out = ByteArray(w * h * 4)
            spots.forEach { RetouchMath.heal(src, w, h, it, true, out) }
            for (i in 0 until w * h) { val m = mask[i].toInt() and 0xFF; for (k in 0..3) out[i * 4 + k] = ((out[i * 4 + k].toInt() and 0xFF) * m / 255).toByte() }
        }
        val cache = RetouchCache(w, h)
        val fewer = spots.dropLast(1)
        fun timed(block: () -> Unit): Double { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6 }
        val adds = mutableListOf<Double>()
        val removes = mutableListOf<Double>()
        val moves = mutableListOf<Double>()
        repeat(9) { k ->
            removes += timed { cache.render(src, 1, fewer, emptyList(), true, mask, 1, false) }
            adds += timed { cache.render(src, 1, spots, emptyList(), true, mask, 1, false) }
            moves += timed { cache.render(src, 1, spots.mapIndexed { i, s -> if (i == 3) s.copy(sourceX = s.sourceX + k + 1) else s }, emptyList(), true, mask, 1, false) }
        }
        val add = adds.sorted()[4]
        val addOnly = removes.sorted()[4]
        val move = moves.sorted()[4]
        val ref = RetouchCache(w, h).apply { render(src, 2, spots, emptyList(), true, mask, 1, false) }
        cache.render(src, 1, spots, emptyList(), true, mask, 1, false)
        assertContentEquals(ref.out, cache.out)
        println("RETOUCH_TIMING full=%.2fms add_spot=%.2fms remove_redraw=%.2fms move_source=%.2fms".format(full, add, addOnly, move))
    }
}
