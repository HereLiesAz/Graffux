package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.feature.editor.threed.SurfaceBrush
import org.junit.Assert.assertEquals
import org.junit.Test

/** The 2D brush sliders as a 3D surface dab sees them. */
class SurfaceBrushTest {

    private fun state(
        feathering: Float = 0f,
        opacity: Float = 1f,
        flow: Float = 1f,
        brush: String? = "Round",
    ) = EditorUiState(
        brushFeathering = feathering, brushOpacity = opacity, brushFlow = flow, activeBrushName = brush,
    )

    @Test
    fun `hardness is one minus feathering`() {
        assertEquals(1f, SurfaceBrush.of(state(feathering = 0f), 0, erase = false).hardness, 0f)
        assertEquals(0.25f, SurfaceBrush.of(state(feathering = 0.75f), 0, erase = false).hardness, 1e-6f)
        assertEquals(0f, SurfaceBrush.of(state(feathering = 1f), 0, erase = false).hardness, 0f)
    }

    @Test
    fun `out of range sliders are clamped`() {
        val b = SurfaceBrush.of(state(feathering = 3f, opacity = -1f, flow = 9f), 0, erase = false)
        assertEquals(0f, b.hardness, 0f)
        assertEquals(0f, b.opacity, 0f)
        assertEquals(1f, b.flow, 0f)
    }

    @Test
    fun `flow belongs to a stamp brush and the built-in brush ignores it`() {
        assertEquals(0.3f, SurfaceBrush.of(state(flow = 0.3f, brush = "Round"), 0, erase = false).flow, 1e-6f)
        assertEquals(1f, SurfaceBrush.of(state(flow = 0.3f, brush = null), 0, erase = false).flow, 0f)
    }

    @Test
    fun `opacity applies to the brush, and to the eraser only as a stamp eraser`() {
        assertEquals(0.4f, SurfaceBrush.of(state(opacity = 0.4f, brush = null), 0, erase = false).opacity, 1e-6f)
        assertEquals(0.4f, SurfaceBrush.of(state(opacity = 0.4f, brush = "Round"), 0, erase = true).opacity, 1e-6f)
        assertEquals(1f, SurfaceBrush.of(state(opacity = 0.4f, brush = null), 0, erase = true).opacity, 0f)
    }
}
