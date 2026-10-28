package com.hereliesaz.graffitixr.common.photo

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RetouchCacheTest {

    private val w = 64
    private val h = 48
    private val src = ByteArray(w * h * 4) { i -> if (i % 4 == 3) -1 else ((i * 37) % 251).toByte() }
    private val mask = ByteArray(w * h) { ((it * 13) % 256).toByte() }

    private fun spot(x: Float, y: Float) = HealSpot(x = x, y = y, radius = 5f, sourceX = x + 12f, sourceY = y)

    private fun fresh(heal: List<HealSpot>, red: List<RedEyeSpot>, m: ByteArray?, inv: Boolean = false) =
        RetouchCache(w, h).apply { render(src, 1, heal, red, true, m, 7, inv) }.out

    @Test
    fun `incremental renders equal a full render`() {
        for (m in listOf(null, mask)) {
            val c = RetouchCache(w, h)
            val a = listOf(spot(10f, 10f))
            val b = a + spot(14f, 12f)
            val red = listOf(RedEyeSpot(x = 30f, y = 30f, radius = 4f))
            c.render(src, 1, a, emptyList(), true, m, 7, false)
            c.render(src, 1, b, red, true, m, 7, false)
            assertContentEquals(fresh(b, red, m), c.out)
            // Moving a source and removing a spot: only rectangles redrawn, same result.
            val moved = listOf(b[0].copy(sourceX = 5f, sourceY = 30f))
            c.render(src, 1, moved, red, true, m, 7, false)
            assertContentEquals(fresh(moved, red, m), c.out)
            c.render(src, 1, moved, red, true, m, 7, true)
            assertContentEquals(fresh(moved, red, m, inv = true), c.out)
        }
    }

    @Test
    fun `adding a spot dirties only that spot`() {
        val c = RetouchCache(w, h)
        c.render(src, 1, listOf(spot(10f, 10f)), emptyList(), true, null, 0, false)
        c.render(src, 1, listOf(spot(10f, 10f), spot(40f, 30f)), emptyList(), true, null, 0, false)
        val d = c.dirty!!
        assertEquals(listOf(34, 24, 47, 37), d.toList())
    }
}
