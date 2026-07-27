package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.model.EditorUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Brush Studio's reducer transitions. (The clamping itself lives with AzphaltBrush and is covered
 * by AzphaltBrushTest, next to the params parser it has to agree with.)
 */
class BrushStudioTest {

    @Test
    fun `opening the studio stores a sanitized draft`() {
        // The draft is what the canvas paints with, so an out-of-range value must never reach it —
        // clamping on the way in is what lets the UI bind sliders straight to the draft.
        val opened = EditorReducer.reduce(
            EditorUiState(),
            EditorIntent.SetBrushStudioDraft(AzphaltBrush(name = "X", spacing = 99f), editingId = "id-1"),
        )
        assertNotNull(opened.brushStudioDraft)
        assertEquals(4f, opened.brushStudioDraft!!.spacing, 0f)
        assertEquals("id-1", opened.brushStudioEditingId)
    }

    @Test
    fun `closing the studio clears the draft and the edited id`() {
        val opened = EditorReducer.reduce(
            EditorUiState(),
            EditorIntent.SetBrushStudioDraft(AzphaltBrush(name = "X"), editingId = "id-1"),
        )
        val closed = EditorReducer.reduce(opened, EditorIntent.SetBrushStudioDraft(null))
        assertNull(closed.brushStudioDraft)
        // Leaving the id behind would make the next Save silently overwrite a brush the user is no
        // longer editing.
        assertNull(closed.brushStudioEditingId)
    }

    @Test
    fun `a brand-new brush has no editing id`() {
        val opened = EditorReducer.reduce(
            EditorUiState(),
            EditorIntent.SetBrushStudioDraft(AzphaltBrush(name = "New")),
        )
        assertNull(opened.brushStudioEditingId)
        assertEquals("New", opened.brushStudioDraft!!.name)
    }
}
