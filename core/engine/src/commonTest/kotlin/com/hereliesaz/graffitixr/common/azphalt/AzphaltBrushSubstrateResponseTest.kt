package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class AzphaltBrushSubstrateResponseTest {
    @Test
    fun defaults_to_zero() {
        assertEquals(0f, AzphaltBrush(name = "b").substrateResponse)
    }

    @Test
    fun params_without_the_key_parse_as_zero() {
        val brush = AzphaltBrush.fromParams("b", buildJsonObject { put("opacity", 0.5f) })
        assertEquals(0f, brush.substrateResponse)
        assertEquals(0f, AzphaltBrush.fromParams("b", null).substrateResponse)
    }

    @Test
    fun params_key_is_read_and_clamped() {
        assertEquals(0.4f, AzphaltBrush.fromParams("b", buildJsonObject { put("substrateResponse", 0.4f) }).substrateResponse)
        assertEquals(1f, AzphaltBrush.fromParams("b", buildJsonObject { put("substrateResponse", 3f) }).substrateResponse)
        assertEquals(0f, AzphaltBrush.fromParams("b", buildJsonObject { put("substrateResponse", -1f) }).substrateResponse)
    }

    @Test
    fun is_independent_of_the_impasto_material_response() {
        val brush = AzphaltBrush.fromParams(
            "b",
            buildJsonObject {
                put("impastoSubstrateResponse", JsonPrimitive(0.7f))
            },
        )
        assertEquals(0f, brush.substrateResponse)
        assertEquals(0.7f, brush.impastoMaterial.substrateResponse)
    }

    @Test
    fun sanitized_clamps() {
        assertEquals(1f, AzphaltBrush(name = "b", substrateResponse = 2f).sanitized().substrateResponse)
        assertEquals(0f, AzphaltBrush(name = "b", substrateResponse = -2f).sanitized().substrateResponse)
    }

    @Test
    fun serialization_round_trips_and_old_json_decodes_as_zero() {
        val brush = AzphaltBrush(name = "b", substrateResponse = 0.65f)
        val decoded = AzphaltJson.decodeFromString(AzphaltBrush.serializer(), AzphaltJson.encodeToString(AzphaltBrush.serializer(), brush))
        assertEquals(brush, decoded)

        val legacy = AzphaltJson.decodeFromString(AzphaltBrush.serializer(), """{"name":"old","opacity":0.5}""")
        assertEquals(0f, legacy.substrateResponse)
    }
}
