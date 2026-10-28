package com.hereliesaz.graffux

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dropping a dragged layer thumbnail on the trash (LayersRail) — the hit test. */
class LayerTrashDropTest {
    private val trash = Rect(500f, 1800f, 556f, 1856f)

    @Test
    fun `a drop inside the trash deletes`() {
        assertTrue(droppedOnTrash(Offset(528f, 1828f), trash))
    }

    @Test
    fun `a drop just outside its edge still counts, a forgiving target for a finger`() {
        assertTrue(droppedOnTrash(Offset(490f, 1828f), trash))
    }

    @Test
    fun `a drop elsewhere, or before the trash was placed, does not delete`() {
        assertFalse(droppedOnTrash(Offset(900f, 600f), trash))
        assertFalse(droppedOnTrash(Offset(528f, 1828f), null))
        assertFalse(droppedOnTrash(Offset(0f, 0f), Rect.Zero))
    }
}
