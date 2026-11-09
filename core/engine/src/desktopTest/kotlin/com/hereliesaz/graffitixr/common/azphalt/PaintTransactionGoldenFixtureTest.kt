package com.hereliesaz.graffitixr.common.azphalt

import com.hereliesaz.graffitixr.common.azphalt.PaintTransaction.CH
import com.hereliesaz.graffitixr.common.azphalt.PaintTransaction.Dab
import com.hereliesaz.graffitixr.common.azphalt.PaintTransaction.Load
import com.hereliesaz.graffitixr.common.azphalt.PaintTransaction.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the paint-transaction reference to the fixture `core/wgpu-engine/tests/transaction.rs` feeds the
 * WGSL port. Delete the file and re-run to regenerate; a diff in review means the model changed.
 */
class PaintTransactionGoldenFixtureTest {
    private val path = File("../wgpu-engine/tests/fixtures/transaction_golden.txt")
    private val n = 24

    private fun scenario(): Triple<Surface, Load, List<Dab>> {
        val s = Surface(n, n)
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            fun blob(cx: Int, cy: Int, r2: Int, vararg tubes: Pair<Int, Float>, amt: Float) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= r2) {
                    s.amount[i] = amt
                    val q = PigmentConcentration.quantize(FloatArray(CH).also { c -> for ((t, w) in tubes) c[t] = w })
                    for (c in 0 until CH) s.conc[i * CH + c] = q[c] / 255f
                }
            }
            blob(6, 12, 25, 0 to 1f, amt = 0.8f)
            blob(18, 12, 25, 1 to 1f, 2 to 0.5f, amt = 0.6f)
            blob(12, 20, 9, 3 to 1f, 12 to 0.25f, amt = 0.9f)
        }
        val load = Load(4, 1.2f)
        val dabs = ArrayList<Dab>()
        repeat(5) { dabs += Dab(6f, 12f, 4f, 0.5f, 0, lift = 0.25f) }
        repeat(5) { dabs += Dab(18f, 12f, 4f, 0.3f, 1, lift = 0.2f) }
        dabs += Dab(12f, 20f, 3f, 1f, 2, lift = 0.4f)
        dabs += Dab(12f, 6f, 5f, 0.4f, 0, draw = 0.3f)
        dabs += Dab(12f, 6f, 5f, 0.4f, 0, draw = 0.3f, mixWet = 0.8f)
        dabs += Dab(18f, 12f, 4f, 0.6f, 1, draw = 0.2f, mixWet = 0.6f)
        dabs += Dab(6f, 12f, 3f, 1f, -1, lift = 0.5f, dispose = true)
        dabs += Dab(12f, 12f, 6f, 0.2f, 2, draw = 5f, mixWet = 0.3f)
        return Triple(s, load, dabs)
    }

    private fun f(v: Float) = v.toString()
    private fun q(v: Float) = Math.round(v * 255f).toString()

    private fun render(): String {
        val (s, load, dabs) = scenario()
        val out = ArrayList<String>()
        out += "# paint transaction golden: S w h / A amount / C conc(q) / L lanes capacity / D dabs / O* expected"
        out += "S $n $n"
        out += "A " + s.amount.joinToString(" ") { f(it) }
        out += "C " + s.conc.joinToString(" ") { q(it) }
        out += "L ${load.lanes} ${f(load.capacity)}"
        for (d in dabs) out += "D ${f(d.x)} ${f(d.y)} ${f(d.radius)} ${f(d.hardness)} ${d.lane} ${f(d.draw)} ${f(d.lift)} ${f(d.mixWet)} ${if (d.dispose) 1 else 0}"
        val avail = PaintTransaction.run(s, load, dabs)
        out += "OA " + s.amount.joinToString(" ") { f(it) }
        out += "OC " + s.conc.joinToString(" ") { q(it) }
        out += "OV " + avail.joinToString(" ") { f(it) }
        out += "OL " + load.amount.joinToString(" ") { f(it) }
        out += "OLC " + load.conc.joinToString(" ") { f(it) }
        return out.joinToString("\n") + "\n"
    }

    @Test
    fun theFixtureMatchesTheReference() {
        val want = render()
        if (!path.exists()) {
            path.parentFile.mkdirs()
            path.writeText(want)
        }
        assertEquals(want, path.readText(), "transaction_golden.txt is stale: delete it and re-run this test to regenerate")
    }
}
