// FILE: core/common/src/test/java/com/hereliesaz/graffitixr/common/model/VectorPathsTest.kt
package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorPathsTest {

    @Test
    fun bounds_returnsNullForIncompletePoint() {
        assertNull(VectorPaths.bounds(emptyList()))
        assertNull(VectorPaths.bounds(listOf(5f))) // dangling x, no y
    }

    @Test
    fun bounds_spansAllPoints() {
        val b = VectorPaths.bounds(listOf(10f, 20f, -5f, 40f, 3f, 0f))!!
        assertArrayEquals(floatArrayOf(-5f, 0f, 10f, 40f), b, 1e-4f)
    }

    @Test
    fun pathShape_recentersOnOriginAndSizesToBounds() {
        // A 100x40 box positioned away from the origin.
        val shape = VectorPaths.pathShape(
            points = listOf(100f, 100f, 200f, 100f, 200f, 140f, 100f, 140f),
            closed = true,
            fillArgb = 0xFF204080L,
        )!!
        assertEquals(ShapeKind.PATH, shape.kind)
        assertEquals(100f, shape.width, 1e-4f)
        assertEquals(40f, shape.height, 1e-4f)
        // Center was (150,120); points shift to be symmetric about origin.
        assertArrayEquals(
            floatArrayOf(-50f, -20f, 50f, -20f, 50f, 20f, -50f, 20f),
            shape.points.toFloatArray(), 1e-4f,
        )
        assertTrue(shape.closed)
        assertTrue(shape.hasFill) // closed + opaque fill
    }

    @Test
    fun openPath_isStrokeOnlyEvenWithFillColor() {
        val open = VectorPaths.pathShape(
            points = listOf(0f, 0f, 10f, 10f, 20f, 0f),
            closed = false,
            fillArgb = 0xFFFF0000L,
            strokeWidth = 4f,
        )!!
        assertFalse(open.hasFill)  // open paths never fill
        assertTrue(open.hasStroke)
    }

    @Test
    fun pathShape_nullForEmptyPoints() {
        assertNull(VectorPaths.pathShape(emptyList()))
    }
}
