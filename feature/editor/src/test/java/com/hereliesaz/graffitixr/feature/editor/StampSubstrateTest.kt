package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.AzphaltBrush
import com.hereliesaz.graffitixr.common.azphalt.SubstratePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The live, commit and replay paths all resolve a stroke's GPU substrate through [StampSubstrate.of]. */
class StampSubstrateTest {
    private val responsive = AzphaltBrush(name = "tooth", substrateResponse = 0.6f)
    private val historical = AzphaltBrush(name = "plain")

    @Test
    fun `a zero-response brush paints the historical path on every surface`() {
        SubstratePreset.entries.forEach { assertNull(StampSubstrate.of(it.id, historical)) }
    }

    @Test
    fun `smooth, legacy and unknown surfaces paint the historical path`() {
        assertNull(StampSubstrate.of("smooth", responsive))
        assertNull(StampSubstrate.of(null, responsive))
        assertNull(StampSubstrate.of("velvet", responsive))
    }

    @Test
    fun `a textured surface with a responsive brush carries the preset profile and response`() {
        val substrate = StampSubstrate.of("cold_press", responsive)
        assertNotNull(substrate)
        substrate!!
        val profile = SubstratePreset.COLD_PRESS.profile
        assertEquals(SubstratePreset.COLD_PRESS, substrate.preset)
        assertEquals(0.6f, substrate.response)
        assertEquals(profile.baseHeight, substrate.params.baseHeight)
        assertEquals(profile.heightScale, substrate.params.heightScale)
        assertEquals(profile.textureScale, substrate.params.textureScale)
    }

    @Test
    fun `the stroke's snapshot, not the current project, decides the paint`() {
        val stroke = StrokeCommand(
            path = emptyList(),
            canvasSize = androidx.compose.ui.unit.IntSize(10, 10),
            tool = com.hereliesaz.graffitixr.common.model.Tool.BRUSH,
            brushSize = 4f,
            brushColor = 0,
            intensity = 0.5f,
            stampBrush = responsive,
            substrateId = "brick_concrete",
        )
        assertEquals(SubstratePreset.BRICK_CONCRETE, StampPaint.of(stroke, responsive).substrate?.preset)
        assertNull(StampPaint.of(stroke.copy(substrateId = null), responsive).substrate)
    }
}
