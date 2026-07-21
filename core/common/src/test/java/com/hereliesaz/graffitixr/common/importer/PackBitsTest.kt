// FILE: core/common/src/test/java/com/hereliesaz/graffitixr/common/importer/PackBitsTest.kt
package com.hereliesaz.graffitixr.common.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PackBitsTest {

    private fun decode(src: IntArray, dstCount: Int): Pair<ByteArray, Int> {
        val s = ByteArray(src.size) { src[it].toByte() }
        val dst = ByteArray(dstCount)
        val consumed = PackBits.decode(s, 0, dst, 0, dstCount)
        return dst to consumed
    }

    @Test
    fun literalRun_copiesBytesVerbatim() {
        // control 0x02 => literal run of 3 bytes.
        val (out, consumed) = decode(intArrayOf(0x02, 0xAA, 0xBB, 0xCC), 3)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), out)
        assertEquals(4, consumed)
    }

    @Test
    fun replicateRun_repeatsSingleByte() {
        // control 0xFD = -3 => repeat next byte (1 - (-3)) = 4 times.
        val (out, consumed) = decode(intArrayOf(0xFD, 0x7E), 4)
        assertArrayEquals(ByteArray(4) { 0x7E }, out)
        assertEquals(2, consumed)
    }

    @Test
    fun noOpControlByte_isSkipped() {
        // 0x80 = -128 => no-op, then a literal run.
        val (out, _) = decode(intArrayOf(0x80, 0x00, 0x11), 1)
        assertArrayEquals(byteArrayOf(0x11), out)
    }

    @Test
    fun mixedRuns_fillExactlyDstCount() {
        // replicate 2x 0x01, then literal 0x02,0x03.
        val (out, consumed) = decode(intArrayOf(0xFF, 0x01, 0x01, 0x02, 0x03), 4)
        assertArrayEquals(byteArrayOf(1, 1, 2, 3), out)
        assertEquals(5, consumed)
    }
}
