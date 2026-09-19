package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WetMaterialTransportTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun channelSum(pixels: IntArray, shift: Int): Int =
        pixels.sumOf { it ushr shift and 0xFF }

    @Test
    fun idleFieldDoesNoColourWork() {
        val field = PersistentWetnessField(8, 8, tileSize = 4)
        val pixels = IntArray(64) { argb(255, 10, 20, 30) }
        val before = pixels.copyOf()

        val stats = WetMaterialTransport.advanceArgb(
            pixels, 8, 8, field, deltaSeconds = 1f, transportRate = 1f,
        )

        assertContentEquals(before, pixels)
        assertEquals(0, stats.activeTilesProcessed)
        assertEquals(0, stats.pixelsVisited)
        assertEquals(0, stats.edgesProcessed)
    }

    @Test
    fun transportIsDeterministicAndConservesPackedChannelMass() {
        val fieldA = PersistentWetnessField(4, 1, tileSize = 4)
        val fieldB = PersistentWetnessField(4, 1, tileSize = 4)
        repeat(4) {
            fieldA.addWetness(it, 0, 1f)
            fieldB.addWetness(it, 0, 1f)
        }
        val initial = intArrayOf(
            argb(255, 255, 0, 0),
            argb(255, 255, 0, 0),
            argb(255, 0, 0, 255),
            argb(255, 0, 0, 255),
        )
        val a = initial.copyOf()
        val b = initial.copyOf()
        val sums = intArrayOf(24, 16, 8, 0).map { channelSum(initial, it) }

        WetMaterialTransport.advanceArgb(a, 4, 1, fieldA, 0.5f, 0.8f, iterations = 2)
        WetMaterialTransport.advanceArgb(b, 4, 1, fieldB, 0.5f, 0.8f, iterations = 2)

        assertContentEquals(a, b)
        intArrayOf(24, 16, 8, 0).forEachIndexed { index, shift ->
            assertEquals(sums[index], channelSum(a, shift), "channel shift $shift lost mass")
        }
        assertTrue((a[1] and 0xFF) > 0, "blue should diffuse into the red side")
        assertTrue((a[2] ushr 16 and 0xFF) > 0, "red should diffuse into the blue side")
    }

    @Test
    fun inactiveAdjacentTileIsNeverModified() {
        val width = 8
        val field = PersistentWetnessField(width, 1, tileSize = 4)
        repeat(4) { field.addWetness(it, 0, 1f) }
        val pixels = IntArray(width) { x ->
            if (x < 4) argb(255, 255, 0, 0) else argb(255, 0, 0, 255)
        }
        val rightBefore = pixels.copyOfRange(4, 8)

        val stats = WetMaterialTransport.advanceArgb(
            pixels, width, 1, field, deltaSeconds = 1f, transportRate = 1f,
        )

        assertContentEquals(rightBefore, pixels.copyOfRange(4, 8))
        assertEquals(1, stats.activeTilesProcessed)
        assertEquals(8, stats.pixelsVisited) // one 4-pixel tile × two fixed iterations
    }

    @Test
    fun dryPixelsInsideAnActiveTileDoNotMoveColour() {
        val field = PersistentWetnessField(2, 1, tileSize = 2)
        field.addWetness(0, 0, 1f)
        val pixels = intArrayOf(argb(255, 255, 0, 0), argb(255, 0, 0, 255))
        val before = pixels.copyOf()

        // Average mobility on the only edge is 0.5, so some transport should occur.
        WetMaterialTransport.advanceArgb(pixels, 2, 1, field, 1f, 1f, iterations = 1)
        assertTrue(!pixels.contentEquals(before))

        val dry = PersistentWetnessField(2, 1, tileSize = 2)
        dry.activate(DirtyRegion(0, 0, 2, 1))
        val dryPixels = before.copyOf()
        WetMaterialTransport.advanceArgb(dryPixels, 2, 1, dry, 1f, 1f, iterations = 1)
        assertContentEquals(before, dryPixels)
    }
}
