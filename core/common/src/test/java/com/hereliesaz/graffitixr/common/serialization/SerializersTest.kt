package com.hereliesaz.graffitixr.common.serialization

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializersTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `OffsetSerializer serializes and deserializes correctly`() {
        val original = Offset(10.5f, 20.0f)
        val serialized = json.encodeToString(OffsetSerializer, original)
        val deserialized = json.decodeFromString(OffsetSerializer, serialized)

        assertEquals("\"10.5,20.0\"", serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun `a layer with non-finite float fields still saves, as their neutral values`() {
        val layer = com.hereliesaz.graffitixr.common.model.Layer(
            id = "l", name = "l", scale = Float.NaN, rotationZ = Float.POSITIVE_INFINITY, opacity = Float.NaN,
        )
        val decoded = json.decodeFromString(
            com.hereliesaz.graffitixr.common.model.Layer.serializer(),
            json.encodeToString(com.hereliesaz.graffitixr.common.model.Layer.serializer(), layer),
        )
        assertEquals(1f, decoded.scale, 0f)
        assertEquals(0f, decoded.rotationZ, 0f)
        assertEquals(1f, decoded.opacity, 0f)
    }

    @Test
    fun `OffsetSerializer handles invalid input gracefully`() {
        val invalidJson = "\"invalid\""
        val deserialized = json.decodeFromString(OffsetSerializer, invalidJson)
        assertEquals(Offset.Zero, deserialized)

        val partialJson = "\"10.5\""
        val deserializedPartial = json.decodeFromString(OffsetSerializer, partialJson)
        assertEquals(Offset.Zero, deserializedPartial)
    }

    @Test
    fun `BlendModeSerializer serializes and deserializes correctly`() {
        val modes = listOf(
            BlendMode.SrcOver,
            BlendMode.Clear,
            BlendMode.Multiply,
            BlendMode.Screen,
            BlendMode.Overlay
        )

        modes.forEach { mode ->
            val serialized = json.encodeToString(BlendModeSerializer, mode)
            val deserialized = json.decodeFromString(BlendModeSerializer, serialized)
            assertEquals(mode, deserialized)
        }
    }

    @Test
    fun `BlendModeSerializer defaults to SrcOver for unknown strings`() {
        val unknownJson = "\"UnknownMode\""
        val deserialized = json.decodeFromString(BlendModeSerializer, unknownJson)
        assertEquals(BlendMode.SrcOver, deserialized)
    }
}
