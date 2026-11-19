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
        val withTail = packWheelSlots(4)
        val withoutTail = List(4) { PackWheelSlot.Pack(it) }
        assertEquals(withoutTail, withTail.take(4))
        // Wheel at position 1: packs 0..3 sit at -24, 0, 24, 48 degrees (right), mirrored (left).
        val right = listOf(-24f, 0f, 24f, 48f)
        val left = listOf(204f, 180f, 156f, 132f)
        for (ring in listOf(withTail, withoutTail)) {
            for (i in 0 until 4) {
                assertEquals(PackWheelSlot.Pack(i), ring[i])
                assertEquals(right[i], packSlotDegrees(ring.indexOf(PackWheelSlot.Pack(i)), 1f, true), 1e-4f)
                assertEquals(left[i], packSlotDegrees(ring.indexOf(PackWheelSlot.Pack(i)), 1f, false), 1e-4f)
            }
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
    fun hidingForTheStoreHidesAPinnedWheelButKeepsItPinned() {
        val state = PackWheelState()
        state.pinTo(true)
        state.close()
        assertTrue(state.open)
        state.hideForStore()
        assertEquals(false, state.open)
        assertTrue("still pinned to the sheet", state.pinned)
        // Back from the store: reopened (or the tool tapped), it is a pinned wheel again.
        state.toggle()
        assertTrue(state.open)
        state.close()
        assertTrue("a pinned wheel ignores close()", state.open)
        state.pinTo(false)
        assertEquals(false, state.open)
    }
}
