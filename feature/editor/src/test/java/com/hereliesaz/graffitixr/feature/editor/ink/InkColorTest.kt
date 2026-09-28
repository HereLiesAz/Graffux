package com.hereliesaz.graffitixr.feature.editor.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class InkColorTest {

    @Test
    fun `full opacity leaves the colour alone`() {
        assertEquals(0xFF123456.toInt(), InkColor.withOpacity(0xFF123456.toInt(), 1f))
    }

    @Test
    fun `opacity scales only the alpha`() {
        assertEquals(0x7F123456, InkColor.withOpacity(0xFF123456.toInt(), 0.5f))
    }

    @Test
    fun `opacity multiplies an already translucent colour and is clamped`() {
        assertEquals(0x40ABCDEF, InkColor.withOpacity(0x80ABCDEF.toInt(), 0.5f))
        assertEquals(0x00ABCDEF, InkColor.withOpacity(0xFFABCDEF.toInt(), -1f))
        assertEquals(0xFFABCDEF.toInt(), InkColor.withOpacity(0xFFABCDEF.toInt(), 2f))
    }
}
