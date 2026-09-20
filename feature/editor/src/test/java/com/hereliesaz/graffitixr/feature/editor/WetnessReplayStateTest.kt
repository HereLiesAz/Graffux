package com.hereliesaz.graffitixr.feature.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WetnessReplayStateTest {
    @Test
    fun `recorded uptime deterministically advances pigment and drying`() {
        fun run(): Pair<IntArray, FloatArray> {
            val state = WetnessReplayState.empty(4, 1, tileSize = 4)
            state.field.addWetness(0, 0, 1f)
            state.field.addWetness(1, 0, 1f)
            val pixels = intArrayOf(
                0xFFFF0000.toInt(), 0xFFFF0000.toInt(),
                0xFF0000FF.toInt(), 0xFF0000FF.toInt(),
            )
            state.markThrough(1_000L)
            state.advanceMaterialTo(pixels, 1_500L)
            return pixels to state.snapshot()
        }

        val a = run()
        val b = run()
        assertArrayEquals(a.first, b.first)
        assertArrayEquals(a.second, b.second, 0f)
        assertTrue(a.second.sum() < 2f)
    }

    @Test
    fun `backwards uptime starts a new epoch without advancing material`() {
        val state = WetnessReplayState.empty(2, 1, tileSize = 2)
        state.field.addWetness(0, 0, 1f)
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt())
        val beforePixels = pixels.copyOf()
        val beforeWetness = state.snapshot()

        state.markThrough(5_000L)
        state.advanceMaterialTo(pixels, 4_000L)

        assertArrayEquals(beforePixels, pixels)
        assertArrayEquals(beforeWetness, state.snapshot(), 0f)
        assertEquals(4_000L, state.lastUptimeMillis)
    }

    @Test
    fun `fresh wet contact visibly settles a colour boundary with fixed bounded work`() {
        val state = WetnessReplayState.empty(4, 1, tileSize = 4)
        repeat(4) { state.field.addWetness(it, 0, 1f) }
        val pixels = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF0000.toInt(),
            0xFF0000FF.toInt(), 0xFF0000FF.toInt(),
        )
        val before = pixels.copyOf()
        val wetnessBefore = state.snapshot().sum()

        state.settleMaterial(pixels)

        assertTrue(!pixels.contentEquals(before))
        assertTrue((pixels[1] and 0xFF) > 0)
        assertTrue((pixels[2] ushr 16 and 0xFF) > 0)
        assertEquals(
            "contact settle transports wetness but must not apply elapsed-time drying",
            wetnessBefore, state.snapshot().sum(), 1e-5f,
        )
    }

    @Test
    fun `Impasto wetness-only advancement never transports display RGB`() {
        val state = WetnessReplayState.empty(2, 1, tileSize = 2)
        state.field.addWetness(0, 0, 1f)
        state.field.addWetness(1, 0, 1f)
        state.markThrough(1_000L)

        state.advanceWetnessTo(2_000L, dryingRate = 0.5f, wetnessTransportRate = 0f)

        assertTrue(state.snapshot().sum() < 2f)
        assertEquals(2_000L, state.lastUptimeMillis)
    }

    @Test
    fun `copyForWork is a defensive material snapshot`() {
        val original = WetnessReplayState.empty(2, 1, tileSize = 2)
        original.field.addWetness(0, 0, 0.5f)
        original.markThrough(123L)

        val copy = original.copyForWork()
        copy.field.addWetness(0, 0, 0.5f)
        copy.markThrough(456L)

        assertEquals(0.5f, original.field.wetnessAt(0, 0), 0f)
        assertEquals(123L, original.lastUptimeMillis)
        assertEquals(1f, copy.field.wetnessAt(0, 0), 0f)
        assertEquals(456L, copy.lastUptimeMillis)
    }
    @Test
    fun `material drying override changes only explicit elapsed-time decay`() {
        fun remaining(dryingRate: Float): Float {
            val state = WetnessReplayState.empty(2, 1, tileSize = 2)
            state.field.addWetness(0, 0, 1f)
            val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt())
            state.markThrough(1_000L)
            state.advanceMaterialTo(
                pixels,
                2_000L,
                dryingRate = dryingRate,
                wetnessTransportRate = 0f,
                pigmentTransportRate = 0f,
            )
            return state.snapshot().sum()
        }

        assertEquals(1f, remaining(0f), 1e-6f)
        assertTrue(remaining(1f) < remaining(0.1f))
    }

}
