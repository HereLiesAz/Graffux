package com.hereliesaz.graffitixr.common.azphalt

import com.hereliesaz.graffitixr.common.azphalt.PaintTransaction.CH
import com.hereliesaz.graffitixr.common.azphalt.PaintTransaction.Dab
import com.hereliesaz.graffitixr.common.azphalt.PaintTransaction.Load
import com.hereliesaz.graffitixr.common.azphalt.PaintTransaction.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** spec/painterly.md 3.4: lift consumes the source, no self-pickup, disposal, capacity, finite draw, mixing. */
class PaintTransactionTest {
    private val n = 24

    /** Two blobs of pure tube paint: tube 0 at (6,12), tube 1 at (18,12), amount 0.8 each. */
    private fun board(): Surface {
        val s = Surface(n, n)
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            fun blob(cx: Int, tube: Int) {
                val dx = x - cx
                val dy = y - 12
                if (dx * dx + dy * dy <= 25) {
                    s.amount[i] = 0.8f
                    s.conc[i * CH + tube] = 1f
                }
            }
            blob(6, 0)
            blob(18, 1)
        }
        return s
    }

    private fun total(s: Surface) = s.amount.sum()

    private fun lift(lane: Int, x: Float, y: Float, amount: Float = 0.3f, dispose: Boolean = false) =
        Dab(x, y, 4f, 0.5f, lane, lift = amount, dispose = dispose)

    @Test
    fun liftingMovesPaintIntoTheColumnAndConservesIt() {
        val s = board()
        val load = Load(3, 100f)
        val before = total(s)
        PaintTransaction.run(s, load, List(6) { lift(0, 6f, 12f) })
        assertTrue(load.amount[0] > 0.5f, "the brush picked something up")
        assertEquals(before, total(s) + load.amount.sum(), 1e-3f)
        assertEquals(1f, load.conc[0 * CH + 0], 1e-4f, "it is tube 0 paint")
        assertEquals(0f, load.conc[0 * CH + 1], 1e-6f)
    }

    @Test
    fun repeatedLiftsFromOnePatchCannotRefillIndefinitely() {
        val s = board()
        val load = Load(3, 1000f)
        val initial = total(s)
        PaintTransaction.run(s, load, List(200) { lift(0, 6f, 12f, amount = 0.5f) })
        assertTrue(load.amount[0] <= initial / 2f + 1e-3f, "cannot lift more than was there: ${load.amount[0]}")
        // the patch is spent
        val i = 12 * n + 6
        assertTrue(s.amount[i] < 1e-4f)
    }

    @Test
    fun aStrokeNeverLiftsItsOwnDeposit() {
        val s = board()
        val load = Load(3, 100f)
        // fill column 1 from blob B, draw it onto the empty left-bottom corner, then lift there
        val dabs = List(4) { lift(1, 18f, 12f) } +
            Dab(4f, 4f, 3f, 1f, 1, draw = 0.4f) +
            List(4) { lift(2, 4f, 4f) }
        PaintTransaction.run(s, load, dabs)
        assertTrue(s.amount[4 * n + 4] > 0.1f, "paint was deposited there")
        assertEquals(0f, load.amount[2], 1e-6f, "but this stroke can't pick it back up")
    }

    @Test
    fun aDisposalLiftRemovesPaintWithoutLoadingIt() {
        val s = board()
        val load = Load(3, 100f)
        val before = total(s)
        PaintTransaction.run(s, load, List(5) { lift(-1, 6f, 12f, dispose = true) })
        assertEquals(0f, load.amount.sum(), 0f)
        assertTrue(total(s) < before - 1f, "the ground shows where it scratched")
    }

    @Test
    fun aColumnTakesOnlyWhatFits() {
        val s = board()
        val load = Load(3, 0.1f)
        PaintTransaction.run(s, load, List(10) { lift(0, 6f, 12f) })
        assertTrue(load.amount[0] <= 0.1f + 1e-5f)
        assertTrue(load.amount[0] > 0.09f)
    }

    @Test
    fun drawingIsFiniteAndNeverExceedsTheLoad() {
        val s = board()
        val load = Load(3, 100f).also { it.amount[0] = 0.5f; it.conc[0] = 1f }
        val before = total(s)
        PaintTransaction.run(s, load, List(20) { Dab(12f, 4f, 4f, 0.5f, 0, draw = 5f) })
        assertEquals(0f, load.amount[0], 1e-5f)
        assertEquals(before + 0.5f, total(s), 1e-3f)
    }

    @Test
    fun wetMixingKeepsTheSurfaceRecipeWhileAPureDrawBlendsByAmount() {
        fun shareOfB(mix: Float): Float {
            val s = board()
            val load = Load(3, 100f).also { it.amount[0] = 5f; it.conc[0] = 1f }
            PaintTransaction.run(s, load, listOf(Dab(18f, 12f, 3f, 1f, 0, draw = 0.4f, mixWet = mix)))
            return s.conc[(12 * n + 18) * CH + 1]
        }
        val pure = shareOfB(0f)
        val wet = shareOfB(1f)
        assertTrue(pure < 0.95f, "pure tube-0 paint over tube-1 paint shifts the recipe: $pure")
        assertTrue(wet > pure, "full wet mixing keeps the surface's recipe: $wet vs $pure")
    }

    @Test
    fun everyTouchedTexelStaysAValidRecipeSummingTo255() {
        val s = board()
        val load = Load(3, 100f)
        PaintTransaction.run(s, load, List(3) { lift(0, 6f, 12f) } + Dab(12f, 12f, 6f, 0.3f, 0, draw = 0.2f, mixWet = 0.5f))
        for (i in 0 until n * n) {
            var sum = 0f
            for (c in 0 until CH) sum += s.conc[i * CH + c]
            if (s.amount[i] > 0f) assertEquals(1f, sum, 2e-3f)
        }
    }

    @Test
    fun theSameDabsGiveTheSameResult() {
        val dabs = List(4) { lift(0, 6f, 12f) } + Dab(12f, 12f, 5f, 0.4f, 0, draw = 0.3f, mixWet = 0.6f)
        val a = board().also { PaintTransaction.run(it, Load(3, 5f), dabs) }
        val b = board().also { PaintTransaction.run(it, Load(3, 5f), dabs) }
        assertTrue(a.conc.contentEquals(b.conc) && a.amount.contentEquals(b.amount))
    }
}
