package com.hereliesaz.graffitixr.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureMappingTest {

    @Test
    fun `no gesture is destructive by default`() {
        // Audit round 2, #18: an accidental three-finger scrub used to clear the active layer.
        assertTrue(DEFAULT_GESTURE_MAPPING.values.none { it == GestureAction.CLEAR_LAYER })
        assertEquals(GestureAction.UNDO, DEFAULT_GESTURE_MAPPING[GestureSlot.THREE_FINGER_SCRUB])
    }

    @Test
    fun `clear layer is still a choice`() {
        assertTrue(GestureAction.CLEAR_LAYER in GestureAction.entries)
    }
}
