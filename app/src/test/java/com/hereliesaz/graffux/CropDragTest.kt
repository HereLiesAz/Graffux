package com.hereliesaz.graffux

import com.hereliesaz.graffitixr.common.photo.DocumentCrop
import org.junit.Assert.assertEquals
import org.junit.Test

/** The crop frame's drag rules: corners, edges, moving, the aspect lock and the minimum size. */
class CropDragTest {
    private val full = DocumentCrop()

    @Test
    fun `edges and corners move their own sides`() {
        val c = dragCrop(full, CropHandle.TOP_LEFT, 0.1f, 0.2f, null, 1f)
        assertEquals(listOf(0.1f, 0.2f, 1f, 1f), listOf(c.left, c.top, c.right, c.bottom))
        val r = dragCrop(full, CropHandle.RIGHT, -0.3f, 0.5f, null, 1f)
        assertEquals(listOf(0f, 0f, 0.7f, 1f), listOf(r.left, r.top, r.right, r.bottom))
        val m = dragCrop(full.copy(right = 0.5f), CropHandle.MOVE, 0.25f, -0.1f, null, 1f)
        assertEquals(listOf(0.25f, -0.1f, 0.75f, 0.9f), listOf(m.left, m.top, m.right, m.bottom))
    }

    @Test
    fun `an aspect holds on a corner and nothing shrinks below the minimum`() {
        // A square document, 4:5: height = width * 1 / 0.8.
        val c = dragCrop(full, CropHandle.BOTTOM_RIGHT, -0.2f, 0f, 0.8f, 1f)
        assertEquals(0.8f, c.right, 1e-6f)
        assertEquals(1f, c.bottom, 1e-6f)
        val tiny = dragCrop(full, CropHandle.LEFT, 2f, 0f, null, 1f)
        assertEquals(1f - MIN_CROP, tiny.left, 1e-6f)
    }
}
