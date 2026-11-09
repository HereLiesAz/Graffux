package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** spec/painterly.md E5: per-dab height multiplier (`emit h`, `Dab.heightMul`) and dry sheen. */
class PainterlyRidgeTest {
    private val w = 24
    private val hgt = 24

    private fun dab(heightMul: Float = 1f) = Dab(x = 12f, y = 12f, radius = 6f, alpha = 1f, heightMul = heightMul)

    private fun deposited(heightMul: Float): FloatArray {
        val h = FloatArray(w * hgt)
        ImpastoEngine.deposit(h, w, hgt, dab(heightMul), hardness = 1f, thicknessRate = 0.3f)
        return h
    }

    @Test
    fun defaultHeightMulIsTodaysDeposit() {
        val h = deposited(1f)
        val legacy = FloatArray(w * hgt)
        ImpastoEngine.deposit(legacy, w, hgt, Dab(x = 12f, y = 12f, radius = 6f, alpha = 1f), 1f, 0.3f)
        assertTrue(h.contentEquals(legacy))
    }

    @Test
    fun heightMulScalesHeightNotCoverage() {
        val none = deposited(0f)
        val one = deposited(1f)
        val two = deposited(2f)
        val c = 12 * w + 12
        assertEquals(0f, none[c])
        assertTrue(two[c] > one[c] && one[c] > 0f)
        // same footprint: a pixel is touched by one exactly when it is touched by two
        assertTrue(one.indices.all { (one[it] > 0f) == (two[it] > 0f) })
    }

    @Test
    fun materialTransferHeightMulRaisesHeightAndSpendsMoreLoad() {
        val medium = PaintMedium(heightResponse = 1f, depositionRate = 1f)
        fun run(m: Float): Pair<Float, Float> {
            val h = FloatArray(w * hgt)
            val stats = ImpastoEngine.transferMaterialStroke(h, w, hgt, listOf(dab(m)), 1f, 0.3f, medium)
            return h[12 * w + 12] to stats.state.reservoirLoad
        }
        val (h1, load1) = run(1f)
        val (h2, load2) = run(2f)
        assertTrue(h2 > h1)
        assertTrue(load2 < load1, "a thicker bead draws more paint off the brush: $load1 vs $load2")
    }

    @Test
    fun anEmitCanSetHeightAndAnOutputScalesTheBrushsOwnDabs() {
        val samples = BrushSampleBuilder().let { b -> (0..60 step 2).mapIndexed { i, x -> b.add(x.toFloat(), 40f, i * 8L, 0.8f) } }
        fun dabs(step: String) = LiveStrokeDabs.movementDabs(
            samples, 16f,
            AzphaltBrush(name = "p", spacing = 0.25f, program = StrokeProgramSpec(emptyMap(), "", step)), 3L,
        )
        assertTrue(dabs("").all { it.heightMul == 1f })
        assertTrue(dabs("hide = 1; emit { x: px, y: py, size: 4, h: 2.5 };").all { it.heightMul == 2.5f })
        assertTrue(dabs("heightMul = 0.5;").all { it.heightMul == 0.5f })
        assertTrue(dabs("hide = 1; emit { x: px, y: py, size: 4 };").all { it.heightMul == 1f })
    }

    private fun bump(): FloatArray = FloatArray(w * hgt) { i ->
        val dx = (i % w) - 12f
        val dy = (i / w) - 12f
        (0.6f - 0.04f * (dx * dx + dy * dy) / 4f).coerceAtLeast(0f)
    }

    private fun shaded(sheen: Float): IntArray {
        val raw = IntArray(w * hgt) { 0xFF808080.toInt() }
        val out = IntArray(w * hgt)
        ImpastoEngine.shadeMaterialInto(
            out, raw, bump(), null, w, hgt, 0, 0, w, hgt,
            lightAzimuthDeg = 135f, lightElevationDeg = 45f, reliefStrength = 1f,
            medium = PaintMedium(baseRoughness = 0.3f, sheen = sheen),
        )
        return out
    }

    @Test
    fun zeroSheenIsTodaysPresentation() {
        val raw = IntArray(w * hgt) { 0xFF808080.toInt() }
        val old = IntArray(w * hgt)
        ImpastoEngine.shadeMaterialInto(
            old, raw, bump(), null, w, hgt, 0, 0, w, hgt, 135f, 45f, 1f, PaintMedium(baseRoughness = 0.3f),
        )
        assertTrue(old.contentEquals(shaded(0f)))
    }

    @Test
    fun aDryBeadKeepsAHighlightThatGrowsWithSheen() {
        fun peak(px: IntArray) = px.maxOf { (it shr 8) and 0xFF }
        val none = peak(shaded(0f))
        val some = peak(shaded(0.4f))
        val more = peak(shaded(0.9f))
        assertTrue(some > none, "sheen adds a highlight with no wetness: $none -> $some")
        assertTrue(more >= some)
    }

    @Test
    fun sheenIsSanitizedAndParsedFromParams() {
        assertEquals(1f, PaintMedium(sheen = 4f).sanitized().sheen)
        assertEquals(0f, PaintMedium(sheen = -1f).sanitized().sheen)
        val params = Json.parseToJsonElement("""{"impastoMaterial":{"version":2,"enabled":true,"sheen":0.6}}""") as JsonObject
        assertEquals(0.6f, AzphaltBrush.fromParams("b", params).impastoMaterial.toMedium().sheen)
        assertEquals(0f, ImpastoMaterialConfig().toMedium().sheen)
    }
}
