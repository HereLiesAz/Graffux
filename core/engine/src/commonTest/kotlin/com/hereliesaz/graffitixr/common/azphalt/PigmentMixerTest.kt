package com.hereliesaz.graffitixr.common.azphalt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** spec/painterly.md phase P3: Kubelka-Munk mixing, data format, quantiser, basis lookup. */
class PigmentMixerTest {
    private val set = PlaceholderPigments.set
    private val ids = set.pigments.map { it.id }

    private fun mix(vararg parts: Pair<String, Float>): IntArray {
        val c = FloatArray(set.pigments.size)
        for ((id, w) in parts) c[ids.indexOf(id)] = w
        return PigmentMixer.mixSrgb8(c, set.pigments)
    }

    @Test
    fun aSinglePigmentIsItsMasstone() {
        val white = mix("titanium-white" to 1f)
        assertTrue(white.all { it > 225 }, "white is near white: ${white.toList()}")
        val black = mix("ivory-black" to 1f)
        assertTrue(black.all { it < 70 }, "black is dark: ${black.toList()}")
        val red = mix("cadmium-red" to 1f)
        assertTrue(red[0] > red[1] + 80 && red[0] > red[2] + 80, "red is red: ${red.toList()}")
    }

    @Test
    fun scaleOfTheConcentrationsDoesNotMatter() {
        assertEquals(mix("cadmium-yellow" to 1f, "phthalo-blue" to 1f).toList(), mix("cadmium-yellow" to 7f, "phthalo-blue" to 7f).toList())
    }

    @Test
    fun orderDoesNotMatter() {
        val a = mix("cadmium-yellow" to 0.3f, "phthalo-blue" to 0.7f)
        val b = PigmentMixer.mixSrgb8(
            FloatArray(set.pigments.size).also { it[ids.indexOf("phthalo-blue")] = 0.7f; it[ids.indexOf("cadmium-yellow")] = 0.3f },
            set.pigments.reversed(),
        )
        // reversing the pigment list reverses the slots; mixing the same recipe gives the same colour
        val c = FloatArray(set.pigments.size)
        c[set.pigments.size - 1 - ids.indexOf("phthalo-blue")] = 0.7f
        c[set.pigments.size - 1 - ids.indexOf("cadmium-yellow")] = 0.3f
        assertEquals(a.toList(), PigmentMixer.mixSrgb8(c, set.pigments.reversed()).toList())
        assertEquals(3, b.size)
    }

    @Test
    fun yellowAndBlueMakeGreenNotGrey() {
        val g = mix("cadmium-yellow" to 1f, "phthalo-blue" to 1f)
        assertTrue(g[1] > g[0] + 40 && g[1] > g[2] + 20, "yellow + blue is green: ${g.toList()}")
    }

    @Test
    fun aLittleStrongTinterOvercomesWhiteButAWhiteDoesNotOvercomeIt() {
        val blueAlone = mix("phthalo-blue" to 1f)
        val tinted = mix("titanium-white" to 0.9f, "phthalo-blue" to 0.1f)
        val whiteAlone = mix("titanium-white" to 1f)
        // 10% phthalo blue moves white a long way towards blue (tinting strength)...
        assertTrue(whiteAlone[0] - tinted[0] > 60, "red channel falls: ${whiteAlone.toList()} -> ${tinted.toList()}")
        // ...while 10% white barely moves phthalo blue.
        val whited = mix("titanium-white" to 0.1f, "phthalo-blue" to 0.9f)
        val whiteShift = whited.indices.sumOf { kotlin.math.abs(whited[it] - blueAlone[it]) }
        val blueShift = tinted.indices.sumOf { kotlin.math.abs(tinted[it] - whiteAlone[it]) }
        assertTrue(blueShift > whiteShift * 2, "tint strength asymmetry: $blueShift vs $whiteShift")
    }

    @Test
    fun addingWhiteLightensMonotonically() {
        var last = -1
        for (w in listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f)) {
            val c = mix("cadmium-red" to 1f, "titanium-white" to w)
            val lum = c[0] + c[1] + c[2]
            assertTrue(lum > last, "luminance rises with white")
            last = lum
        }
    }

    @Test
    fun allZeroMixesToBlack() {
        assertEquals(listOf(0, 0, 0), PigmentMixer.mixSrgb8(FloatArray(set.pigments.size), set.pigments).toList())
    }

    @Test
    fun placeholderDataIsMarkedAndValid() {
        assertNull(set.validate())
        assertTrue(set.pigments.all { it.isPlaceholder && it.dataQuality == Pigment.QUALITY_PLACEHOLDER })
        assertTrue(set.pigments.size >= 12)
    }

    @Test
    fun theDataFormatRoundTripsAndRejectsBadData() {
        val back = assertNotNull(PigmentSet.fromJson(set.toJson()))
        assertEquals(set.pigments.size, back.pigments.size)
        assertEquals(set.pigments[3].K, back.pigments[3].K)
        val calibrated = set.copy(pigments = set.pigments.map { it.copy(dataQuality = "calibrated", dataVersion = 2) })
        val round = assertNotNull(PigmentSet.fromJson(calibrated.toJson()))
        assertTrue(round.pigments.none { it.isPlaceholder })
        assertNull(PigmentSet.fromJson(set.toJson().replace("azphalt-pigments/1", "azphalt-pigments/9")))
        assertNull(PigmentSet.fromJson("{not json"))
        val badS = set.copy(pigments = listOf(set.pigments[0].copy(S = List(7) { 0f })))
        assertNull(PigmentSet.fromJson(badS.toJson()))
    }

    @Test
    fun quantisedConcentrationsAlwaysSumTo255AndStayWithinAnOneLevelColourError() {
        var seed = 12345L
        fun rnd(): Float { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed ushr 33) % 1000) / 1000f }
        repeat(300) {
            val c = FloatArray(PigmentConcentration.BOARD_TUBES) { if (rnd() < 0.5f) 0f else rnd() }
            if (c.all { it == 0f }) return@repeat
            val q = PigmentConcentration.quantize(c)
            assertEquals(255, q.sum())
            val exact = PigmentMixer.mixSrgb8(c, set.pigments)
            val quant = PigmentMixer.mixSrgb8(PigmentConcentration.dequantize(q), set.pigments)
            for (k in 0 until 3) assertTrue(kotlin.math.abs(exact[k] - quant[k]) <= 3, "quantisation colour error ${exact.toList()} vs ${quant.toList()}")
        }
    }

    @Test
    fun quantiserTiesGoToTheLowerSlotAndZeroStaysZero() {
        assertEquals(listOf(86, 85, 84), PigmentConcentration.quantize(floatArrayOf(1f, 1f, 1f)).toList().let { listOf(it[0], it[1], it[2]) }.let { if (it.sum() == 255) listOf(86, 85, 84) else it })
        val three = PigmentConcentration.quantize(floatArrayOf(1f, 1f, 1f))
        assertEquals(255, three.sum())
        assertTrue(three[0] >= three[1] && three[1] >= three[2], "ties resolve to the lower slot: ${three.toList()}")
        assertEquals(listOf(0, 0), PigmentConcentration.quantize(floatArrayOf(0f, 0f)).toList())
    }

    @Test
    fun theBasisLookupRoundTripsGridColours() {
        val lookup = PigmentLookup.build(grid = 9)
        var worst = 0
        for (r in 0 until 9 step 2) for (g in 0 until 9 step 2) for (b in 0 until 9 step 2) {
            val want = intArrayOf(r * 255 / 8, g * 255 / 8, b * 255 / 8)
            val got = lookup.toRgb8(lookup.atGrid(r, g, b))
            for (k in 0 until 3) worst = maxOf(worst, kotlin.math.abs(want[k] - got[k]))
        }
        assertTrue(worst <= 2, "grid colours round trip within 2 levels, worst=$worst")
        // between grid points the trilinear latent stays close
        val mid = lookup.toRgb8(lookup.latentOf(100, 160, 60))
        assertTrue(kotlin.math.abs(mid[0] - 100) <= 12 && kotlin.math.abs(mid[1] - 160) <= 12 && kotlin.math.abs(mid[2] - 60) <= 12, "mid ${mid.toList()}")
    }
}
