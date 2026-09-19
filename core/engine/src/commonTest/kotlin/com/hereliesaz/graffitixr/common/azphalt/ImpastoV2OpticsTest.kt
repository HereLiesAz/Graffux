package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImpastoV2OpticsTest {
    @Test
    fun zeroWetGlossIsExactHistoricalRegionShader() {
        val w = 12
        val h = 12
        val height = FloatArray(w * h)
        height[6 * w + 6] = 1f
        val raw = IntArray(w * h) { 0xFF506070.toInt() }

        val historical = ImpastoRegionShader.shade(
            raw, height, w, h, 0, 0, w, h, 315f, 45f, 0.6f,
        )
        val wetness = PersistentWetnessField(w, h).also { it.addWetness(6, 6, 1f) }
        val v2Disabled = ImpastoRegionShader.shade(
            raw, height, w, h, 0, 0, w, h, 315f, 45f, 0.6f,
            wetness = wetness, wetGlossStrength = 0f,
        )

        assertContentEquals(historical, v2Disabled)
    }

    @Test
    fun wetGlossBrightensDerivedSurfaceWithoutMutatingPigmentInput() {
        val w = 8
        val h = 8
        val height = FloatArray(w * h) { 0.5f } // flat surface: no relief change
        val raw = IntArray(w * h) { 0xFF303840.toInt() }
        val before = raw.copyOf()
        val wetness = PersistentWetnessField(w, h)
        wetness.addWetness(4, 4, 1f)

        val dryDisplay = ImpastoRegionShader.shade(
            raw, height, w, h, 0, 0, w, h, 315f, 45f, 0.6f,
        )
        val wetDisplay = ImpastoRegionShader.shade(
            raw, height, w, h, 0, 0, w, h, 315f, 45f, 0.6f,
            wetness = wetness, wetGlossStrength = 1f,
        )

        assertContentEquals(before, raw)
        assertContentEquals(raw, dryDisplay)
        val idx = 4 * w + 4
        val dryRed = dryDisplay[idx] ushr 16 and 0xFF
        val wetRed = wetDisplay[idx] ushr 16 and 0xFF
        assertTrue(wetRed > dryRed)
        assertEquals(raw[idx], before[idx])
    }

    @Test
    fun dryingWetnessTransitionsOpticsBackTowardMatte() {
        val w = 8
        val h = 8
        val height = FloatArray(w * h) { 0.5f }
        val raw = IntArray(w * h) { 0xFF404040.toInt() }
        val wetness = PersistentWetnessField(w, h)
        wetness.addWetness(4, 4, 1f)

        fun red(): Int {
            val out = ImpastoRegionShader.shade(
                raw, height, w, h, 0, 0, w, h, 315f, 45f, 0f,
                wetness = wetness, wetGlossStrength = 1f,
            )
            return out[4 * w + 4] ushr 16 and 0xFF
        }

        val wetRed = red()
        wetness.advance(deltaSeconds = 20f, dryingRate = 0.5f, transportRate = 0f)
        val drierRed = red()
        assertTrue(drierRed < wetRed)
    }
}
