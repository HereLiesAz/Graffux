// FILE: core/common/src/test/java/com/hereliesaz/graffitixr/common/importer/PsdVectorMaskTest.kt
package com.hereliesaz.graffitixr.common.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import org.junit.Test

class PsdVectorMaskTest {

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun be32(v: Int) =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** A 26-byte length record (selector 0 closed / 3 open) declaring [knots] knots. */
    private fun lengthRecord(selector: Int, knots: Int) = ByteArrayOutputStream().apply {
        write(be16(selector)); write(be16(knots)); write(ByteArray(22))
    }.toByteArray()

    /** A 26-byte Bézier knot record: selector + control0 + anchor(vert,horiz) + control2. */
    private fun knot(selector: Int, vert: Int, horiz: Int) = ByteArrayOutputStream().apply {
        write(be16(selector))
        write(ByteArray(8))          // preceding control point (ignored)
        write(be32(vert)); write(be32(horiz)) // anchor
        write(ByteArray(8))          // leaving control point (ignored)
    }.toByteArray()

    private fun block(vararg records: ByteArray) = ByteArrayOutputStream().apply {
        write(ByteArray(8)) // version + flags header
        records.forEach { write(it) }
    }.toByteArray()

    private val HALF = 0x00800000   // 0.5 in 8.24 fixed point
    private val QUARTER = 0x00400000 // 0.25
    private val THREE_Q = 0x00C00000 // 0.75

    @Test
    fun parsesClosedSubpathAnchorsToDocumentPixels() {
        val bytes = block(
            lengthRecord(0, 3),                 // closed subpath, 3 knots
            knot(2, HALF, HALF),                // (x=500, y=250)
            knot(2, QUARTER, THREE_Q),          // (x=750, y=125)
            knot(2, THREE_Q, QUARTER),          // (x=250, y=375)
        )
        val paths = PsdVectorMask.parse(bytes, docW = 1000, docH = 500)
        assertEquals(1, paths.size)
        assertTrue(paths[0].closed)
        assertArrayEquals(
            floatArrayOf(500f, 250f, 750f, 125f, 250f, 375f),
            paths[0].points.toFloatArray(), 1e-2f,
        )
    }

    @Test
    fun openSubpathIsNotClosed_andFillRuleRecordsIgnored() {
        val bytes = block(
            byteArrayOf(0, 6) + ByteArray(24),  // selector 6 = path fill rule (no geometry)
            lengthRecord(3, 2),                 // open subpath
            knot(5, 0, 0),                      // (0,0)
            knot(5, HALF, HALF),                // (500,250)
        )
        val paths = PsdVectorMask.parse(bytes, docW = 1000, docH = 500)
        assertEquals(1, paths.size)
        assertFalse(paths[0].closed)
        assertEquals(4, paths[0].points.size) // two points
    }

    @Test
    fun dropsSubpathsWithFewerThanTwoPoints() {
        val bytes = block(lengthRecord(0, 1), knot(2, HALF, HALF)) // single point → dropped
        assertTrue(PsdVectorMask.parse(bytes, 1000, 500).isEmpty())
    }

    @Test
    fun emptyForTooShortOrBadDims() {
        assertTrue(PsdVectorMask.parse(ByteArray(4), 100, 100).isEmpty())
        assertTrue(PsdVectorMask.parse(block(lengthRecord(0, 2)), 0, 100).isEmpty())
    }
}
