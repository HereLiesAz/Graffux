package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KritaBrushAssetParsingTest {
    private fun params(json: String): JsonObject =
        AzphaltJson.decodeFromString(JsonObject.serializer(), json)

    @Test
    fun `parses ratio texture and masked brush parameters`() {
        val brush = AzphaltBrush.fromParams(
            "Textured Mask",
            params(
                """{
                    "spacing":0.18,
                    "isotropicSpacing":false,
                    "tipRatio":0.42,
                    "shape":"tips/primary.png",
                    "grain":"textures/concrete.png",
                    "grainScale":1.75,
                    "grainStrength":0.65,
                    "grainBehavior":"canvas",
                    "grainBlendMode":"subtract",
                    "grainRandomOffsetPerStroke":true,
                    "grainOffsetX":3.5,
                    "grainOffsetY":-2.0,
                    "maskedBrush":{
                        "shapePath":"tips/mask.png",
                        "sizeRatio":0.7,
                        "tipRatio":0.3,
                        "hardness":0.55,
                        "opacity":0.8,
                        "flow":0.6,
                        "scatter":0.4,
                        "invert":true,
                        "blendMode":"subtract",
                        "dynamics":[
                            {"sensor":"pressure","parameter":"size","outputMin":0.25,"outputMax":1.0}
                        ]
                    }
                }"""
            ),
        )

        assertFalse(brush.isotropicSpacing)
        assertEquals(0.42f, brush.tipRatio, 0f)
        assertEquals("tips/primary.png", brush.shapePath)
        assertEquals("textures/concrete.png", brush.grainPath)
        assertEquals(1.75f, brush.grainScale, 0f)
        assertEquals(0.65f, brush.grainStrength, 0f)
        assertEquals(GrainBehavior.CANVAS_LOCKED, brush.grainBehavior)
        assertEquals(GrainBlendMode.SUBTRACT, brush.grainBlendMode)
        assertTrue(brush.grainRandomOffsetPerStroke)

        val mask = brush.maskedBrush
        assertNotNull(mask)
        mask!!
        assertEquals("tips/mask.png", mask.shapePath)
        assertEquals(0.7f, mask.sizeRatio, 0f)
        assertEquals(0.3f, mask.tipRatio, 0f)
        assertEquals(0.55f, mask.hardness, 0f)
        assertEquals(MaskedBrushBlendMode.SUBTRACT, mask.blendMode)
        assertTrue(mask.invert)
        assertEquals(1, mask.dynamics.size)
        assertEquals(BrushSensor.PRESSURE, mask.dynamics.single().sensor)
        assertEquals(BrushParameter.SIZE, mask.dynamics.single().parameter)
    }
}
