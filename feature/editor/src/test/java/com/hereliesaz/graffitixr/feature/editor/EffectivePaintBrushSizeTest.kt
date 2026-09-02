package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.EditorUiState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [EditorUiState.effectivePaintBrushSize]: the "Brush size locked to screen" setting's whole
 * implementation is this one division, so a sign or direction error here would silently paint the
 * opposite of what the toggle promises (bigger zoomed in instead of the same size, or vice versa) —
 * worth its own test independent of any full stroke/render pipeline.
 */
class EffectivePaintBrushSizeTest {

    private fun state(brushSize: Float, viewportZoom: Float, fixedOnScreen: Boolean) =
        EditorUiState(brushSize = brushSize, viewportZoom = viewportZoom, brushSizeFixedOnScreen = fixedOnScreen)

    @Test
    fun `off, the default, returns brushSize unchanged at any zoom`() {
        assertEquals(50f, state(50f, viewportZoom = 1f, fixedOnScreen = false).effectivePaintBrushSize(), 0f)
        assertEquals(50f, state(50f, viewportZoom = 4f, fixedOnScreen = false).effectivePaintBrushSize(), 0f)
        assertEquals(50f, state(50f, viewportZoom = 0.25f, fixedOnScreen = false).effectivePaintBrushSize(), 0f)
    }

    @Test
    fun `on, zoomed in shrinks the document-space size so the on-screen size stays put`() {
        // Zoomed in 4x: the same on-screen footprint now covers a quarter as much document space.
        val out = state(50f, viewportZoom = 4f, fixedOnScreen = true).effectivePaintBrushSize()
        assertEquals(12.5f, out, 0.001f)
    }

    @Test
    fun `on, zoomed out grows the document-space size so the on-screen size stays put`() {
        // Zoomed out to half: the same on-screen footprint now covers twice as much document space.
        val out = state(50f, viewportZoom = 0.5f, fixedOnScreen = true).effectivePaintBrushSize()
        assertEquals(100f, out, 0.001f)
    }

    @Test
    fun `on, at 1x zoom is a no-op`() {
        assertEquals(50f, state(50f, viewportZoom = 1f, fixedOnScreen = true).effectivePaintBrushSize(), 0f)
    }

    @Test
    fun `on, a zero or negative zoom does not divide by zero or flip sign`() {
        val zero = state(50f, viewportZoom = 0f, fixedOnScreen = true).effectivePaintBrushSize()
        val negative = state(50f, viewportZoom = -1f, fixedOnScreen = true).effectivePaintBrushSize()
        assertEquals(5000f, zero, 0.001f) // 50f / 0.01f coercion floor
        assertEquals(5000f, negative, 0.001f)
    }
}
