package com.hereliesaz.graffux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pack wheel's ring layout and what each slot does: plain JVM, no Compose. */
class PackWheelSlotsTest {

    private val empty = PackWheelSlot.Empty
    private val getMore = PackWheelSlot.GetMore

    @Test
    fun tailIsEmptyGetMoreEmptyAfterThePacks() {
        val slots = packWheelSlots(5)
        assertEquals(5 + 2 * GET_MORE_EMPTY_NEIGHBOURS + 1, slots.size)
        assertEquals(List(5) { PackWheelSlot.Pack(it) }, slots.take(5))
        assertEquals(listOf(empty, getMore, empty), slots.drop(5))
    }

    @Test
    fun getMoreAlwaysHasABlankPlaceEitherSideEvenAcrossTheSeam() {
        for (n in 1..9) {
            val slots = packWheelSlots(n)
            val at = slots.indexOf(getMore)
            assertEquals(1, slots.count { it == getMore })
            repeat(GET_MORE_EMPTY_NEIGHBOURS) { k ->
                assertEquals(empty, slots[wrapIndex(at - 1 - k, slots.size)])
                assertEquals(empty, slots[wrapIndex(at + 1 + k, slots.size)])
            }
        }
    }

    @Test
    fun packsKeepTheirPlacesAndAnglesWithAndWithoutTheTail() {
        // A pack's slot index is its pack index; its angle at a given position depends only on that.
        val slots = packWheelSlots(4)
        slots.take(4).forEachIndexed { i, s -> assertEquals(PackWheelSlot.Pack(i), s) }
        for (i in 0 until 4) {
            assertEquals(packSlotDegrees(i, 1f, true), packSpokeDegrees(i, 1f, true), 1e-4f)
        }
    }

    @Test
    fun neighbouringSlotsAreOneWedgeApart() {
        val slots = packWheelSlots(3)
        val at = slots.indexOf(getMore)
        val here = packSlotDegrees(at, at.toFloat(), true)
        assertEquals(packPointerDegrees(true), here, 1e-4f)
        assertEquals(here - PACK_WEDGE_DEGREES, packSlotDegrees(at - 1, at.toFloat(), true), 1e-4f)
        assertEquals(here + PACK_WEDGE_DEGREES, packSlotDegrees(at + 1, at.toFloat(), true), 1e-4f)
        // Left-handed mirrors.
        val prev = packSlotDegrees(at - 1, at.toFloat(), false)
        assertEquals(packPointerDegrees(false) + PACK_WEDGE_DEGREES, prev, 1e-4f)
    }

    @Test
    fun lastPackToFavoritesPassesThroughTheTail() {
        val slots = packWheelSlots(3)
        val after = (3..6).map { slots[wrapIndex(it, slots.size)] }
        assertEquals(listOf(empty, getMore, empty, PackWheelSlot.Pack(0)), after)
    }

    @Test
    fun selectionRouting() {
        assertEquals(PackWheelAction.Pick(2), routePackSlot(PackWheelSlot.Pack(2), explicit = true))
        assertEquals(PackWheelAction.Pick(2), routePackSlot(PackWheelSlot.Pack(2), explicit = false))
        assertEquals(PackWheelAction.OpenStore, routePackSlot(getMore, explicit = true))
        assertEquals(PackWheelAction.None, routePackSlot(empty, explicit = true))
        assertEquals(PackWheelAction.None, routePackSlot(empty, explicit = false))
    }

    @Test
    fun restingOnGetMoreAfterAFlingNeverOpensTheStore() {
        assertEquals(PackWheelAction.None, routePackSlot(getMore, explicit = false))
    }

    @Test
    fun steppingSkipsTheTail() {
        assertEquals(0, steppedPack(2, 1, 3))
        assertEquals(2, steppedPack(0, -1, 3))
        assertEquals(1, steppedPack(0, 1, 3))
    }

    @Test
    fun noPacksMeansNoRing() {
        assertTrue(packWheelSlots(0).isEmpty())
    }

    @Test
    fun dismissShutsAPinnedWheel() {
        val state = PackWheelState()
        state.pinTo(true)
        state.close()
        assertTrue(state.open)
        state.dismiss()
        assertEquals(false, state.open)
        assertEquals(false, state.pinned)
    }
}
