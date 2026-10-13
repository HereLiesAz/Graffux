package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.unit.IntRect
import com.hereliesaz.graffitixr.common.model.LayerFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayerGrowthTest {
    private val frame = LayerFrame(1000, 2000)

    @Test
    fun `a view inside the bitmap needs no growth`() {
        assertNull(LayerGrowth.coverage(frame, 1000, 2000, IntRect(100, 100, 900, 1900)))
    }

    @Test
    fun `a view past the frame grows on that side in whole steps`() {
        val target = LayerGrowth.coverage(frame, 1000, 2000, IntRect(-300, 0, 1000, 2000))
        // -300 - 64 padding snaps to -512, then clamps at the two-frame cap (-500).
        // The other sides keep the bitmap's edges
        // (the padding past them snaps up to the cap or the next step).
        assertEquals(-500, target?.left)
        assertEquals(0.coerceAtMost(target!!.top), target.top)
        assertEquals(true, target.right >= 1000 && target.bottom >= 2000)
    }

    @Test
    fun `growth stops at the cap of two frames centred on the frame`() {
        val target = LayerGrowth.coverage(frame, 1000, 2000, IntRect(-50_000, -50_000, 50_000, 50_000))
        assertEquals(IntRect(-500, -1000, 1500, 3000), target)
    }

    @Test
    fun `a grown bitmap keeps covering what it covered`() {
        val grown = LayerFrame(1000, 2000, originX = -512, originY = 0)
        // Already 1512 x 2000 from -512; a view on the right only extends the right edge.
        val target = LayerGrowth.coverage(grown, 1512, 2000, IntRect(0, 0, 1200, 2000))
        assertEquals(-512, target?.left)
        assertEquals(true, target!!.right >= 1200)
    }
}
