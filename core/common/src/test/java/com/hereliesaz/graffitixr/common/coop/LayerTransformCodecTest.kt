package com.hereliesaz.graffitixr.common.coop

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayerTransformCodecTest {
    @Test
    fun `the whole plane placement round-trips in the old sixteen-float shape`() {
        val layer = Layer("a", "A", scale = 1.5f, offset = Offset(3f, -4f), rotationX = 10f, rotationY = -20f, rotationZ = 30f, positionZ = 250f)
        val wire = LayerTransformCodec.encode(layer)
        assertEquals(16, wire.size)
        // Slots 0-5 are what older builds read and write, unchanged.
        assertEquals(listOf(1.5f, 3f, -4f, 10f, -20f, 30f), wire.take(6))
        assertEquals(LayerTransformCodec.Transform(1.5f, 3f, -4f, 10f, -20f, 30f, 250f), LayerTransformCodec.decode(wire))
    }

    @Test
    fun `an older peer's message decodes at depth 0`() {
        val old = listOf(1f, 0f, 0f, 0f, 0f, 45f) + List(10) { 0f }
        assertEquals(0f, LayerTransformCodec.decode(old)!!.positionZ)
        assertEquals(0f, LayerTransformCodec.decode(old.take(6))!!.positionZ)
        assertNull(LayerTransformCodec.decode(listOf(1f, 2f)))
    }
}
