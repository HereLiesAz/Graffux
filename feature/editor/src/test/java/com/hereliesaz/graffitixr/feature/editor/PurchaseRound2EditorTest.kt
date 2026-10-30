package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.PackagePrice
import com.hereliesaz.graffitixr.data.azphalt.purchase.BuyPrice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Second-audit #16 (whose price the Buy button shows) and #20 (bounded co-op mask allocation). */
class PurchaseRound2EditorTest {

    @Test fun `a Play route's Buy button shows Google's price, not the repository's`() {
        val repo = PackagePrice(499, "usd")
        assertEquals("Buy · R$ 24,90", buyLabel(repo, BuyPrice.Play("R$ 24,90")))
        // Unknown on a Play build: no number rather than one Play may not charge.
        assertEquals("Buy", buyLabel(repo, null))
        assertEquals("Buy · " + formatPrice(repo), buyLabel(repo, BuyPrice.Repository))
    }

    @Test fun `a peer's oversized or overflowing mask dimensions are refused before allocating`() {
        val tiny = CoopMaskCodec.deflate(ByteArray(4) { 1 })
        assertNull(CoopMaskCodec.inflateMask(tiny, 40_000, 40_000))
        // 65536 x 65537 overflows an Int product to a small positive number.
        assertNull(CoopMaskCodec.inflateMask(tiny, 65_536, 65_537))
        assertNull(CoopMaskCodec.inflateMask(tiny, -2, -2))
        assertNull(CoopMaskCodec.inflateMask(tiny, CoopMaskCodec.MAX_MASK_SIDE + 1, 1))
        assertArrayEquals(ByteArray(4) { 1 }, CoopMaskCodec.inflateMask(tiny, 2, 2))
    }
}
