package com.hereliesaz.graffitixr.common.azphalt

import com.hereliesaz.graffitixr.common.azphalt.WatercolourField.Field
import com.hereliesaz.graffitixr.common.azphalt.WatercolourField.Params
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the watercolour reference to the fixture `core/wgpu-engine/tests/watercolour.rs` feeds the WGSL
 * port. Delete the file and re-run to regenerate; a diff in review means the model changed.
 */
class WatercolourGoldenFixtureTest {
    private val path = File("../wgpu-engine/tests/fixtures/watercolour_golden.txt")
    private val n = 40
    private val ticks = 36
    private val params = Params(wick = 0.18f, bleed = 0.1f, edge = 0.15f, dry = 0.02f, granulation = 0.7f).sanitized()

    private fun render(): String {
        val f = Field(n, n)
        val amount = FloatArray(n * n)
        val water = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            f.absorbency[i] = 0.2f + 0.7f * x / (n - 1)
            f.tooth[i] = ((x * 7 + y * 13) % 11) / 10f
            val dx = x - 14
            val dy = y - 20
            if (dx * dx + dy * dy <= 49) { f.wet[i] = 1f; f.pigment[i] = 0.8f }
            if (y in 8..10 && x in 18..34) { amount[i] = 0.6f; water[i] = 0.9f }
        }
        val out = ArrayList<String>()
        fun fs(a: FloatArray) = a.joinToString(" ") { it.toString() }
        out += "# watercolour golden: S w h / P wick bleed edge dry granulation ticks / W G A T DA DW inputs / OW OG expected"
        out += "S $n $n"
        out += "P ${params.wick} ${params.bleed} ${params.edge} ${params.dry} ${params.granulation} $ticks"
        out += "W " + fs(f.wet)
        out += "G " + fs(f.pigment)
        out += "A " + fs(f.absorbency)
        out += "T " + fs(f.tooth)
        out += "DA " + fs(amount)
        out += "DW " + fs(water)
        WatercolourField.deposit(f, amount, water, params)
        WatercolourField.run(f, params, ticks)
        out += "OW " + fs(f.wet)
        out += "OG " + fs(f.pigment)
        return out.joinToString("\n") + "\n"
    }

    @Test
    fun theFixtureMatchesTheReference() {
        val want = render()
        if (!path.exists()) {
            path.parentFile.mkdirs()
            path.writeText(want)
        }
        assertEquals(want, path.readText(), "watercolour_golden.txt is stale: delete it and re-run this test to regenerate")
    }
}
