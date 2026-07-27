package com.hereliesaz.graffitixr.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteCodecTest {

    @Test
    fun `round-trips a palette`() {
        val palette = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0x8000FFFF.toInt())
        assertEquals(palette, PaletteCodec.decode(PaletteCodec.encode(palette)))
    }

    @Test
    fun `round-trips opaque colours whose alpha overflows a signed int`() {
        // The case a naive toIntOrNull() silently drops: every fully opaque colour.
        val opaqueWhite = 0xFFFFFFFF.toInt()
        assertEquals(listOf(opaqueWhite), PaletteCodec.decode(PaletteCodec.encode(listOf(opaqueWhite))))
    }

    @Test
    fun `decodes empty and blank input as an empty palette`() {
        assertEquals(emptyList<Int>(), PaletteCodec.decode(null))
        assertEquals(emptyList<Int>(), PaletteCodec.decode(""))
        assertEquals(emptyList<Int>(), PaletteCodec.decode("   "))
    }

    @Test
    fun `skips unparseable entries instead of substituting a colour`() {
        // A corrupt entry costs you that swatch; it must never quietly become black.
        val decoded = PaletteCodec.decode("ff0000,zzz,,ff00ff00")
        assertEquals(listOf(0xff0000, 0xff00ff00.toInt()), decoded)
    }

    @Test
    fun `encode caps the palette`() {
        val huge = List(PaletteCodec.MAX_SWATCHES * 2) { it }
        assertEquals(PaletteCodec.MAX_SWATCHES, PaletteCodec.decode(PaletteCodec.encode(huge)).size)
    }

    @Test
    fun `decode caps an oversized stored blob`() {
        val blob = (0 until PaletteCodec.MAX_SWATCHES * 2).joinToString(",") { Integer.toHexString(it) }
        assertEquals(PaletteCodec.MAX_SWATCHES, PaletteCodec.decode(blob).size)
    }

    @Test
    fun `add appends a new colour`() {
        assertEquals(listOf(1, 2), PaletteCodec.add(listOf(1), 2))
    }

    @Test
    fun `add ignores a colour already present`() {
        val palette = listOf(1, 2, 3)
        // Same instance back: the caller uses identity to decide whether a write is needed at all.
        assertSame(palette, PaletteCodec.add(palette, 2))
    }

    @Test
    fun `add drops the oldest swatch when full`() {
        val full = List(PaletteCodec.MAX_SWATCHES) { it }
        val result = PaletteCodec.add(full, -1)
        assertEquals(PaletteCodec.MAX_SWATCHES, result.size)
        assertEquals(-1, result.last())
        assertTrue("the oldest swatch should have been dropped", !result.contains(0))
    }
}
