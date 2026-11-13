package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.scene.ArtBounds
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtScanTest {
    private fun bitmap(w: Int, h: Int, painted: Set<Pair<Int, Int>>, rowsRead: MutableList<Int>? = null): Bitmap {
        val bmp = mockk<Bitmap>(relaxed = true)
        every { bmp.width } returns w
        every { bmp.height } returns h
        every { bmp.getPixels(any<IntArray>(), any(), any(), any(), any(), any(), any()) } answers {
            val row = firstArg<IntArray>()
            val y = arg<Int>(4)
            rowsRead?.add(y)
            for (x in 0 until w) row[x] = if (x to y in painted) 0x01000000 else 0
        }
        return bmp
    }

    @Test
    fun `the bounds hug the painted pixels, right and bottom exclusive`() {
        val b = bitmap(50, 40, setOf(5 to 7, 20 to 30, 12 to 9))
        assertEquals(ArtBounds(5, 7, 21, 31), ArtScan.bounds(b))
    }

    @Test
    fun `art at the bitmap's edge and corners is inside the bounds`() {
        assertEquals(ArtBounds(0, 0, 1, 1), ArtScan.bounds(bitmap(8, 8, setOf(0 to 0))))
        assertEquals(ArtBounds(7, 7, 8, 8), ArtScan.bounds(bitmap(8, 8, setOf(7 to 7))))
        assertEquals(ArtBounds(0, 0, 8, 8), ArtScan.bounds(bitmap(8, 8, setOf(0 to 0, 7 to 7))))
    }

    @Test
    fun `a fully transparent bitmap has no bounds, and a faint pixel counts`() {
        assertNull(ArtScan.bounds(bitmap(8, 8, emptySet())))
        assertEquals(ArtBounds(3, 3, 4, 4), ArtScan.bounds(bitmap(8, 8, setOf(3 to 3))))
    }

    @Test
    fun `it reads one row at a time, never the whole bitmap at once`() {
        val rows = mutableListOf<Int>()
        ArtScan.bounds(bitmap(6, 5, setOf(1 to 1), rows))
        assertEquals(listOf(0, 1, 2, 3, 4), rows)
    }
}
