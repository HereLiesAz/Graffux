package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.azphalt.DirtyRegion
import com.hereliesaz.graffitixr.common.azphalt.PaintMedium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class ImpastoMaterialReplayStateTest {
    @Test
    fun `copy isolates raw pigment and medium ownership`() {
        val raw = IntArray(64) { 0xFF223344.toInt() }
        val state = ImpastoMaterialReplayState.fromRaw(8, 8, raw, tileSize = 4)
        state.recordMedium(
            DirtyRegion(0, 0, 4, 4),
            PaintMedium(viscosity = 0.8f, dryingRate = 0.3f),
        )

        val copy = state.copyForWork()
        assertNotSame(state.rawColor, copy.rawColor)
        copy.rawColor[0] = 0
        copy.recordMedium(
            DirtyRegion(4, 0, 8, 4),
            PaintMedium(viscosity = 0.2f),
        )

        assertEquals(0xFF223344.toInt(), state.rawColor[0])
        assertEquals(0.8f, state.mediumAt(1, 1, PaintMedium()).viscosity, 0f)
        assertEquals(0f, state.mediumAt(5, 1, PaintMedium()).viscosity, 0f)
        assertEquals(0.2f, copy.mediumAt(5, 1, PaintMedium()).viscosity, 0f)
    }

    @Test
    fun `later medium blends only contacted tiles`() {
        val state = ImpastoMaterialReplayState.fromRaw(8, 4, IntArray(32), tileSize = 4)
        state.recordMedium(DirtyRegion(0, 0, 4, 4), PaintMedium(viscosity = 0.8f))
        state.recordMedium(DirtyRegion(0, 0, 4, 4), PaintMedium(viscosity = 0.2f))
        state.recordMedium(DirtyRegion(4, 0, 8, 4), PaintMedium(viscosity = 0.1f))

        assertEquals(0.5f, state.mediumAt(1, 1, PaintMedium()).viscosity, 1e-6f)
        assertEquals(0.1f, state.mediumAt(6, 1, PaintMedium()).viscosity, 1e-6f)
    }
}
