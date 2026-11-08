package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pack wheel's ring maths and its coupling to the brush sheet: plain JVM, no Compose. */
class PackWheelWrapTest {

    @Test
    fun wrapIndexFoldsEitherWay() {
        assertEquals(0, wrapIndex(5, 5))
        assertEquals(4, wrapIndex(-1, 5))
        assertEquals(1, wrapIndex(11, 5))
        assertEquals(3, wrapIndex(-12, 5))
    }

    @Test
    fun offsetTakesTheShortWayRound() {
        assertEquals(1f, wrapOffset(0, 4f, 5), 1e-4f)
        assertEquals(-1f, wrapOffset(4, 0f, 5), 1e-4f)
        assertEquals(0f, wrapOffset(2, 7f, 5), 1e-4f)
        assertEquals(-0.5f, wrapOffset(0, 0.5f, 5), 1e-4f)
    }

    @Test
    fun landingPassesTheEndsInBothDirections() {
        // From the last pack, the first lies one step on; from the first, the last one step back.
        assertEquals(5, packLandingFor(0, 4f, 5))
        assertEquals(-1, packLandingFor(4, 0f, 5))
        assertEquals(0, wrapIndex(packLandingFor(0, 4f, 5), 5))
    }

    @Test
    fun flingIsNotBounded() {
        assertEquals(10, packSnapLanding(4.2f, 30f))
        assertEquals(-6, packSnapLanding(-0.3f, -30f))
    }

    @Test
    fun spokeAfterTheLastIsTheFirst() {
        val last = packSpokeDegreesWrapped(4, 4f, 5, true)
        val first = packSpokeDegreesWrapped(0, 4f, 5, true)
        assertEquals(packPointerDegrees(true), last, 1e-4f)
        assertEquals(packPointerDegrees(true) + PACK_WEDGE_DEGREES, first, 1e-4f)
        val left = packSpokeDegreesWrapped(0, 4f, 5, false)
        assertEquals(packPointerDegrees(false) - PACK_WEDGE_DEGREES, left, 1e-4f)
    }

    @Test
    fun brushSheetUpOnlyOnTheBrushPage() {
        val open = CarouselUi(CarouselCategory.BRUSHES, sheetOpen = true)
        assertTrue(open.brushSheetUp(true, Tool.BRUSH))
        assertTrue(open.brushSheetUp(true, Tool.ERASER))
        assertFalse(open.brushSheetUp(false, Tool.BRUSH))
        assertFalse(open.brushSheetUp(true, Tool.FILL))
        assertFalse(open.copy(sheetOpen = false).brushSheetUp(true, Tool.BRUSH))
        assertFalse(open.copy(photo = true).brushSheetUp(true, Tool.BRUSH))
    }

    @Test
    fun wheelOpensAndStaysWithTheSheet() {
        val state = PackWheelState()
        state.pinTo(true)
        assertTrue(state.open)
        state.close()
        state.toggle()
        assertTrue(state.open)
        state.pinTo(false)
        assertFalse(state.open)
        state.toggle()
        assertTrue(state.open)
        state.close()
        assertFalse(state.open)
    }

    @Test
    fun brushSheetStaysUpOnEveryBrushContextPack() {
        for (c in listOf(CarouselCategory.FAVORITES, CarouselCategory.BRUSHES, CarouselCategory.INK)) {
            assertTrue(c.name, CarouselUi(c, sheetOpen = true).brushSheetUp(true, Tool.BRUSH))
        }
        assertFalse(CarouselUi(CarouselCategory.EFFECTS, sheetOpen = true).brushSheetUp(true, Tool.BRUSH))
    }

    @Test
    fun slotAngleIsContinuousAcrossTheOldFoldPoint() {
        // Three packs: pack 2's single folded copy used to jump from -35.76 to +35.76 at 0.5.
        val before = packSlotDegrees(2, 0.49f, true)
        val after = packSlotDegrees(2, 0.51f, true)
        assertTrue(kotlin.math.abs(after - before) < 1f)
        // Both copies of pack 2 (slots -1 and 2) are drawn at once, at distinct angles.
        val slots = packVisibleSlots(0.5f).filter { wrapIndex(it, 3) == 2 }
        assertEquals(listOf(-1, 2), slots)
    }

    @Test
    fun visibleSlotsStayInsideTheDrawingWindow() {
        for (pos in listOf(0f, 0.3f, 2.5f, -7.2f)) {
            for (n in packVisibleSlots(pos)) {
                assertTrue(kotlin.math.abs(n - pos) * PACK_WEDGE_DEGREES <= 84f + 1e-3f)
            }
        }
    }
}
