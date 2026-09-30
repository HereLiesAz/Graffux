package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.CarouselItemSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The effect-thumbnail cache's key and LRU, without bitmaps (plain JVM). */
class EffectPreviewCacheTest {

    private class Thumb(val name: String) {
        var recycled = false
    }

    private fun cache(max: Int = EFFECT_PREVIEW_CACHE_SIZE) =
        LruBitmapCache<EffectPreviewKey, Thumb>(max) { it.recycled = true }

    @Test
    fun `the key separates effect, parameters and snapshot generation`() {
        val base = EffectPreviewKey("ext-lut:a", null, 1)
        assertEquals(base, EffectPreviewKey("ext-lut:a", null, 1))
        assertNotEquals(base, EffectPreviewKey("ext-lut:b", null, 1))
        assertNotEquals(base, EffectPreviewKey("ext-lut:a", null, 2))
        val s = CarouselItemSettings(size = 10f, flow = 1f, opacity = 1f, softness = 0f)
        assertNotEquals(EffectPreviewKey("k", s, 1), EffectPreviewKey("k", s.copy(size = 11f), 1))
        assertEquals(EffectPreviewKey("k", s, 1), EffectPreviewKey("k", s.copy(), 1))
    }

    @Test
    fun `the LRU holds at most its bound and recycles what it evicts, least recent first`() {
        val c = cache()
        val thumbs = (0 until 20).map { Thumb("t$it") }
        thumbs.forEachIndexed { i, t ->
            c.put(EffectPreviewKey("e$i", null, 1), t)
            // Touching e0 keeps it the most recently used.
            c[EffectPreviewKey("e0", null, 1)]
        }
        assertEquals(EFFECT_PREVIEW_CACHE_SIZE, c.size)
        assertEquals(false, thumbs[0].recycled)
        assertEquals(20 - EFFECT_PREVIEW_CACHE_SIZE, thumbs.count { it.recycled })
        assertEquals(true, thumbs[1].recycled)
        assertEquals(false, thumbs[19].recycled)
    }

    @Test
    fun `a new generation invalidates and recycles every older thumbnail`() {
        val c = cache()
        val old = Thumb("old")
        val fresh = Thumb("fresh")
        c.put(EffectPreviewKey("e", null, 1), old)
        c.put(EffectPreviewKey("e", null, 2), fresh)
        c.removeIf { it.generation != 2L }
        assertNull(c[EffectPreviewKey("e", null, 1)])
        assertEquals(true, old.recycled)
        assertEquals(false, fresh.recycled)
        assertEquals(1, c.size)
    }

    @Test
    fun `replacing a key recycles the value it replaced`() {
        val c = cache()
        val a = Thumb("a")
        c.put(EffectPreviewKey("e", null, 1), a)
        c.put(EffectPreviewKey("e", null, 1), Thumb("b"))
        assertEquals(true, a.recycled)
    }
}
