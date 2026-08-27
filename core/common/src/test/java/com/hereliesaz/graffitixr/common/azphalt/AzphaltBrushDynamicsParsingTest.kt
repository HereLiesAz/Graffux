package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AzphaltBrushDynamicsParsingTest {
    private fun params(json: String): JsonObject =
        AzphaltJson.decodeFromString(JsonObject.serializer(), json)

    @Test
    fun `parses sensor routes from brush params`() {
        val brush = AzphaltBrush.fromParams(
            "Pressure Inker",
            params(
                """{
                    "spacing":0.1,
                    "dynamics":[
                        {
                            "sensor":"pressure",
                            "parameter":"size",
                            "inputMin":0.0,
                            "inputMax":1.0,
                            "outputMin":0.2,
                            "outputMax":1.0
                        },
                        {
                            "sensor":"speed",
                            "parameter":"opacity",
                            "inputMin":0.0,
                            "inputMax":2.0,
                            "outputMin":1.0,
                            "outputMax":0.35,
                            "invert":false
                        }
                    ]
                }"""
            ),
        )

        assertEquals(2, brush.dynamics.size)
        assertEquals(BrushSensor.PRESSURE, brush.dynamics[0].sensor)
        assertEquals(BrushParameter.SIZE, brush.dynamics[0].parameter)
        assertEquals(0.2f, brush.dynamics[0].outputMin, 0f)
        assertEquals(BrushSensor.SPEED, brush.dynamics[1].sensor)
        assertEquals(BrushParameter.OPACITY, brush.dynamics[1].parameter)
    }

    @Test
    fun `malformed sensor route is ignored without killing brush`() {
        val brush = AzphaltBrush.fromParams(
            "Lenient",
            params(
                """{
                    "opacity":0.7,
                    "dynamics":[
                        {"sensor":"not-a-sensor","parameter":"size"},
                        {"sensor":"pressure","parameter":"opacity"}
                    ]
                }"""
            ),
        )

        assertEquals(0.7f, brush.opacity, 0f)
        assertEquals(1, brush.dynamics.size)
        assertEquals(BrushSensor.PRESSURE, brush.dynamics.single().sensor)
    }
}
