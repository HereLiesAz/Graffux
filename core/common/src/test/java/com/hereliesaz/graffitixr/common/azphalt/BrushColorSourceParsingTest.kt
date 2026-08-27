package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class BrushColorSourceParsingTest {
    @Test
    fun `extension params parse color source and mix`() {
        val brush = AzphaltBrush.fromParams(
            "Gradient",
            buildJsonObject {
                put("colorSource", "gradient")
                put("mix", 0.75f)
            },
        )
        assertEquals(BrushColorSource.GRADIENT, brush.colorSource)
        assertEquals(0.75f, brush.colorMix, 0.0001f)
    }
}
