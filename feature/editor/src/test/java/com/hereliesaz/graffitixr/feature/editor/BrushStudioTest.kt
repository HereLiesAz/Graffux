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

/** Node-edit mode's reducer transitions. The geometry itself is covered by PathEditingTest. */
class PathEditModeTest {

    private fun pathLayer(id: String) = com.hereliesaz.graffitixr.common.model.Layer(
        id = id,
        name = id,
        shapes = listOf(
            com.hereliesaz.graffitixr.common.model.VectorShape(
                kind = com.hereliesaz.graffitixr.common.model.ShapeKind.PATH,
                points = listOf(0f, 0f, 10f, 10f),
            ),
        ),
    )

    @Test
    fun `leaving node-edit mode clears the selected node`() {
        // The index refers to a node in a specific path; carrying it out of the mode would leave it
        // pointing at whatever the next edited path happens to have at that position.
        val entered = EditorReducer.reduce(
            EditorUiState(layers = listOf(pathLayer("a"))),
            EditorIntent.SetPathEditLayer("a"),
        )
        val selected = EditorReducer.reduce(entered, EditorIntent.SelectPathNode(1))
        assertEquals(1, selected.selectedNodeIndex)

        val left = EditorReducer.reduce(selected, EditorIntent.SetPathEditLayer(null))
        assertNull(left.pathEditLayerId)
        assertNull(left.selectedNodeIndex)
    }

    @Test
    fun `switching to a different path also clears the selection`() {
        val state = EditorUiState(layers = listOf(pathLayer("a"), pathLayer("b")), pathEditLayerId = "a", selectedNodeIndex = 1)
        val switched = EditorReducer.reduce(state, EditorIntent.SetPathEditLayer("b"))
        assertEquals("b", switched.pathEditLayerId)
        assertNull(switched.selectedNodeIndex)
    }

    @Test
    fun `SetPathShape replaces only the addressed layer's shape`() {
        val state = EditorUiState(layers = listOf(pathLayer("a"), pathLayer("b")))
        val replacement = com.hereliesaz.graffitixr.common.model.VectorShape(
            kind = com.hereliesaz.graffitixr.common.model.ShapeKind.PATH,
            points = listOf(5f, 5f, 20f, 20f),
        )
        val out = EditorReducer.reduce(state, EditorIntent.SetPathShape("b", replacement))
        assertEquals(listOf(0f, 0f, 10f, 10f), out.layers.first { it.id == "a" }.shapes.single().points)
        assertEquals(listOf(5f, 5f, 20f, 20f), out.layers.first { it.id == "b" }.shapes.single().points)
    }
}
